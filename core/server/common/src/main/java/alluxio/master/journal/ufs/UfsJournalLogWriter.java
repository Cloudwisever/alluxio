/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.journal.ufs;

import alluxio.Configuration;
import alluxio.PropertyKey;
import alluxio.RuntimeConstants;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.InvalidJournalEntryException;
import alluxio.exception.JournalClosedException;
import alluxio.master.journal.AbstractJournalSystem;
import alluxio.master.journal.JournalReader;
import alluxio.master.journal.JournalWriter;
import alluxio.proto.journal.Journal.JournalEntry;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.options.CreateOptions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.io.Closer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Class for writing journal edit log entries from the primary master. It marks the current log
 * complete (so that it is visible to the secondary masters) when the current log is large enough.
 *
 * When a new journal writer is created, it also marks the current log complete if there is one.
 *
 * A journal garbage collector thread is created when the writer is created, and is stopped when the
 * writer is closed.
 */
@ThreadSafe
final class UfsJournalLogWriter implements JournalWriter {
  private static final Logger LOG = LoggerFactory.getLogger(UfsJournalLogWriter.class);

  private final UfsJournal mJournal;
  private final UnderFileSystem mUfs;

  /** The maximum size in bytes of a log file. */
  private final long mMaxLogSize;

  /** The next sequence number to use. */
  private long mNextSequenceNumber;
  /** When mRotateForNextWrite is set, mJournalOutputStream must be closed before the next write. */
  private boolean mRotateLogForNextWrite;
  /**
   * The output stream to write the journal log entries.
   * Initially this field is null.
   * Also set this field to null when an {@link IOException} is caught.
   */
  private JournalOutputStream mJournalOutputStream;
  /** The garbage collector. */
  private UfsJournalGarbageCollector mGarbageCollector;
  /** Whether the journal log writer is closed. */
  private boolean mClosed;

  /**
   * Set mNeedsRecovery to true when an IOException is thrown when trying to write journal entries.
   * Clear this flag when {@link #maybeRecoverFromUfsFailures()} successfully recovers.
   */
  private boolean mNeedsRecovery = false;
  /**
   * Journal entries that have been written successfully to the underlying
   * {@link DataOutputStream}, but have not been flushed. Should a failure occur
   * before flush, {@code UfsJournalLogWriter} is able to retry writing the
   * journal entries.
   */
  private Queue<JournalEntry> mEntriesToFlush;

  /**
   * A simple wrapper that wraps a output stream to the current log file.
   */
  private class JournalOutputStream implements Closeable {
    final DataOutputStream mOutputStream;
    final UfsJournalFile mCurrentLog;

    JournalOutputStream(UfsJournalFile currentLog, OutputStream stream) {
      if (stream != null) {
        if (stream instanceof DataOutputStream) {
          mOutputStream = (DataOutputStream) stream;
        } else {
          mOutputStream = new DataOutputStream(stream);
        }
      } else {
        mOutputStream = null;
      }
      mCurrentLog = currentLog;
    }

    /**
     * @return the number of bytes written to this stream
     */
    long bytesWritten() {
      if (mOutputStream == null) {
        return 0;
      }
      return mOutputStream.size();
    }

    /**
     * Closes the stream by committing the log. The implementation must be idempotent as this
     * close can fail and be retried.
     */
    @Override
    public void close() throws IOException {
      if (mOutputStream != null) {
        mOutputStream.close();
      }
      LOG.info("Marking {} as complete with log entries within [{}, {}).",
          mCurrentLog.getLocation(), mCurrentLog.getStart(), mNextSequenceNumber);

      String src = mCurrentLog.getLocation().toString();
      if (!mUfs.exists(src) && mNextSequenceNumber == mCurrentLog.getStart()) {
        // This can happen when there is any failures before creating a new log file after
        // committing last log file.
        return;
      }

      if (AbstractJournalSystem.ALLOW_JOURNAL_MODIFY.get()) {
        completeLog(mCurrentLog, mNextSequenceNumber);
      }
    }
  }

  /**
   * Creates a new instance of {@link UfsJournalLogWriter}.
   *
   * @param journal the handle to the journal
   * @param nextSequenceNumber the sequence number to begin writing at
   */
  UfsJournalLogWriter(UfsJournal journal, long nextSequenceNumber) throws IOException {
    mJournal = Preconditions.checkNotNull(journal, "journal");
    mUfs = mJournal.getUfs();
    mNextSequenceNumber = nextSequenceNumber;
    mMaxLogSize = Configuration.getBytes(PropertyKey.MASTER_JOURNAL_LOG_SIZE_BYTES_MAX);

    mRotateLogForNextWrite = true;
    UfsJournalFile currentLog = UfsJournalSnapshot.getCurrentLog(mJournal);
    if (currentLog != null) {
      mJournalOutputStream = new JournalOutputStream(currentLog, null);
    }
    mGarbageCollector = new UfsJournalGarbageCollector(mJournal);
    mEntriesToFlush = new ArrayDeque<>();
  }

  public synchronized void write(JournalEntry entry) throws IOException, JournalClosedException {
    if (mClosed) {
      throw new IOException(ExceptionMessage.JOURNAL_WRITE_AFTER_CLOSE.getMessage());
    }
    if (!AbstractJournalSystem.ALLOW_JOURNAL_MODIFY.get()) {
      throw new JournalClosedException("Master lost leadership. Cannot write to journal");
    }
    maybeRecoverFromUfsFailures();
    maybeRotateLog();

    try {
      JournalEntry entryToWrite =
          entry.toBuilder().setSequenceNumber(mNextSequenceNumber).build();
      entryToWrite.writeDelimitedTo(mJournalOutputStream.mOutputStream);
      LOG.debug("Adding journal entry (seq={}) to retryList with {} entries.",
          entryToWrite.getSequenceNumber(), mEntriesToFlush.size());
      mEntriesToFlush.add(entryToWrite);
      mNextSequenceNumber++;
    } catch (IOException e) {
      // Set mNeedsRecovery to true so that {@code maybeRecoverFromUfsFailures}
      // can know a UFS failure has occurred.
      mNeedsRecovery = true;
      throw new IOException(ExceptionMessage.JOURNAL_WRITE_FAILURE
          .getMessageWithUrl(RuntimeConstants.ALLUXIO_DEBUG_DOCS_URL,
              mJournalOutputStream.mCurrentLog, e.getMessage()), e);
    }
  }

  /**
   * Core logic of UFS journal recovery from UFS failures.
   *
   * If Alluxio stores its journals in UFS, then Alluxio needs to handle UFS failures.
   * When UFS is dead, there is nothing Alluxio can do because Alluxio relies on UFS to
   * persist journal entries. Consequently any metadata operation will block because Alluxio
   * cannot flush their journal entries.
   * Once UFS comes back online, Alluxio needs to perform the following operations:
   * 1. Find out the sequence number of the last persisted journal entry, say X. Then the first
   *    non-persisted entry has sequence number Y = X + 1.
   * 2. Check whether there is any missing journal entry between Y (inclusive) and the oldest
   *    entry in mEntriesToFlush, say Z. If Z > Y, then it means journal entries in [Y, Z) are
   *    missing, and Alluxio cannot recover. Otherwise, for each journal entry in
   *    {@link #mEntriesToFlush}, if its sequence number is larger than or equal to Y, retry
   *    writing it to UFS by calling the {@code UfsJournalLogWriter#write} method.
   */
  private void maybeRecoverFromUfsFailures() throws IOException {
    if (!mNeedsRecovery) {
      return;
    }

    long lastPersistSeq = recoverLastPersistedJournalEntry();

    createNewLogFile();
    if (!mEntriesToFlush.isEmpty()) {
      JournalEntry firstEntryToFlush = mEntriesToFlush.peek();
      if (firstEntryToFlush.getSequenceNumber() > lastPersistSeq + 1) {
        throw new RuntimeException(ExceptionMessage.JOURNAL_ENTRY_MISSING.getMessageWithUrl(
            RuntimeConstants.ALLUXIO_DEBUG_DOCS_URL,
            lastPersistSeq + 1, firstEntryToFlush.getSequenceNumber()));
      }
      long retryEndSeq = lastPersistSeq;
      LOG.info("Retry writing unwritten journal entries from seq {}", lastPersistSeq + 1);
      for (JournalEntry entry : mEntriesToFlush) {
        if (entry.getSequenceNumber() > lastPersistSeq) {
          try {
            entry.toBuilder().build()
                .writeDelimitedTo(mJournalOutputStream.mOutputStream);
            retryEndSeq = entry.getSequenceNumber();
          } catch (IOException e) {
            throw new IOException(ExceptionMessage.JOURNAL_WRITE_FAILURE
                .getMessageWithUrl(RuntimeConstants.ALLUXIO_DEBUG_DOCS_URL,
                    mJournalOutputStream.mCurrentLog, e.getMessage()), e);
          }
        }
      }
      LOG.info("Finished writing unwritten journal entries from {} to {}.",
          lastPersistSeq + 1, retryEndSeq);
    }
    mNeedsRecovery = false;
  }

  /**
   * Examine the UFS to determine the most recent journal entry, and return its sequence number.
   *
   * 1. Locate the most recent incomplete journal file, i.e. journal file that starts with
   *    a valid sequence number S (hex), and ends with 0x7fffffffffffffff. The journal file
   *    name encodes this information, i.e. S-0x7fffffffffffffff.
   * 2. Sequentially scan the incomplete journal file, and identify the last journal
   *    entry that has been persisted in UFS. Suppose it is X.
   * 3. Rename the incomplete journal file to S-<X+1>. Future journal writes will write to
   *    a new file named <X+1>-0x7fffffffffffffff.
   * 4. If the incomplete journal does not exist or no entry can be found in the incomplete
   *    journal, check the last complete journal file for the last persisted journal entry.
   *
   * @return sequence number of the last persisted journal entry, or -1 if no entry can be found
   */
  private long recoverLastPersistedJournalEntry() throws IOException {
    UfsJournalSnapshot snapshot = UfsJournalSnapshot.getSnapshot(mJournal);
    long lastPersistSeq = -1;
    UfsJournalFile currentLog = snapshot.getCurrentLog(mJournal);
    if (currentLog != null) {
      long startSeq = currentLog.getStart();
      LOG.info("Recovering from previous UFS journal write failure."
          + " Scanning for the last persisted journal entry.");
      try (JournalReader reader = new UfsJournalReader(mJournal, startSeq, true)) {
        JournalEntry entry;
        while ((entry = reader.read()) != null) {
          if (entry.getSequenceNumber() > lastPersistSeq) {
            lastPersistSeq = entry.getSequenceNumber();
          }
        }
      } catch (InvalidJournalEntryException e) {
        LOG.info("Found last persisted journal entry with seq={}.", lastPersistSeq);
      } catch (IOException e) {
        throw e;
      }
      completeLog(currentLog, lastPersistSeq + 1);
    }
    // Search for and scan the latest COMPLETE journal and find out the sequence number of the
    // last persisted journal entry, in case no entry has been found in the INCOMPLETE journal.
    if (lastPersistSeq < 0) {
      // Re-evaluate snapshot because the incomplete journal will be destroyed if
      // it does not contain any valid entry.
      snapshot = UfsJournalSnapshot.getSnapshot(mJournal);
      // journalFiles[journalFiles.size()-1] is the latest complete journal file.
      List<UfsJournalFile> journalFiles = snapshot.getLogs();
      if (!journalFiles.isEmpty()) {
        UfsJournalFile journal = journalFiles.get(journalFiles.size() - 1);
        lastPersistSeq = journal.getEnd() - 1;
        LOG.info("Found last persisted journal entry with seq {} in {}.",
            lastPersistSeq, journal.getLocation().toString());
      }
    }
    return lastPersistSeq;
  }

  /**
   * Closes the current journal output stream and creates a new one.
   * The implementation must be idempotent so that it can work when retrying during failures.
   */
  private void maybeRotateLog() throws IOException {
    if (!mRotateLogForNextWrite) {
      return;
    }
    if (mJournalOutputStream != null) {
      mJournalOutputStream.close();
      mJournalOutputStream = null;
    }

    createNewLogFile();
    mRotateLogForNextWrite = false;
  }

  private void createNewLogFile() throws IOException {
    URI newLog = UfsJournalFile
        .encodeLogFileLocation(mJournal, mNextSequenceNumber, UfsJournal.UNKNOWN_SEQUENCE_NUMBER);
    UfsJournalFile currentLog = UfsJournalFile.createLogFile(newLog, mNextSequenceNumber,
        UfsJournal.UNKNOWN_SEQUENCE_NUMBER);
    OutputStream outputStream = mUfs.create(currentLog.getLocation().toString(),
        CreateOptions.defaults().setEnsureAtomic(false).setCreateParent(true));
    mJournalOutputStream = new JournalOutputStream(currentLog, outputStream);
    LOG.info("Created current log file: {}", currentLog);
  }

  /**
   * Completes the given log.
   *
   * If the log is empty, it will be deleted.
   *
   * This method must be safe to run by multiple masters at the same time. This could happen if a
   * primary master loses leadership and takes a while to close its journal. By the time it
   * completes the current log, the new primary might be trying to close it as well.
   *
   * @param currentLog the log to complete
   * @param nextSequenceNumber the next sequence number for the log to complete
   */
  private void completeLog(UfsJournalFile currentLog, long nextSequenceNumber) throws IOException {
    String current = currentLog.getLocation().toString();
    if (nextSequenceNumber <= currentLog.getStart()) {
      LOG.info("No journal entry found in current journal file {}. Deleting it", current);
      if (!mUfs.deleteFile(current)) {
        LOG.warn("Failed to delete empty journal file {}", current);
      }
      return;
    }
    LOG.info("Completing log {} with next sequence number {}", current, nextSequenceNumber);
    String completed = UfsJournalFile
        .encodeLogFileLocation(mJournal, currentLog.getStart(), nextSequenceNumber).toString();

    if (!mUfs.renameFile(current, completed)) {
      // Completes could happen concurrently, check whether another master already did the rename.
      if (!mUfs.exists(completed)) {
        throw new IOException(
            String.format("Failed to rename journal log from %s to %s", current, completed));
      }
      if (mUfs.exists(current)) {
        // Rename is not atomic, so this could happen if we failed partway through a rename.
        LOG.info("Deleting current log {}", current);
        if (!mUfs.deleteFile(current)) {
          LOG.warn("Failed to delete current log file {}", current);
        }
      }
    }
  }

  public synchronized void flush() throws IOException, JournalClosedException {
    if (!AbstractJournalSystem.ALLOW_JOURNAL_MODIFY.get()) {
      throw new JournalClosedException("Master lost leadership. Cannot write to journal");
    }
    maybeRecoverFromUfsFailures();

    if (mClosed || mJournalOutputStream == null || mJournalOutputStream.bytesWritten() == 0) {
      // There is nothing to flush.
      return;
    }
    DataOutputStream outputStream = mJournalOutputStream.mOutputStream;
    try {
      outputStream.flush();
      // Since flush has succeeded, it's safe to clear the mEntriesToFlush queue
      // because they are considered "persisted" in UFS.
      mEntriesToFlush.clear();
    } catch (IOException e) {
      mRotateLogForNextWrite = true;
      UfsJournalFile currentLog = mJournalOutputStream.mCurrentLog;
      mJournalOutputStream = null;
      throw new IOException(ExceptionMessage.JOURNAL_FLUSH_FAILURE
          .getMessageWithUrl(RuntimeConstants.ALLUXIO_DEBUG_DOCS_URL,
              currentLog, e.getMessage()), e);
    }
    boolean overSize = mJournalOutputStream.bytesWritten() >= mMaxLogSize;
    if (overSize || !mUfs.supportsFlush()) {
      // (1) The log file is oversize, needs to be rotated. Or
      // (2) Underfs is S3 or OSS, flush on S3OutputStream/OSSOutputStream will only flush to
      // local temporary file, call close and complete the log to sync the journal entry to S3/OSS.
      if (overSize) {
        LOG.info("Rotating log file. size: {} maxSize: {}", mJournalOutputStream.bytesWritten(),
            mMaxLogSize);
      }
      mRotateLogForNextWrite = true;
    }
  }

  public synchronized void close() throws IOException {
    Closer closer = Closer.create();
    if (mJournalOutputStream != null) {
      closer.register(mJournalOutputStream);
    }
    closer.register(mGarbageCollector);
    closer.close();
    mClosed = true;
  }

  @VisibleForTesting
  synchronized JournalOutputStream getJournalOutputStream() {
    return mJournalOutputStream;
  }
}
