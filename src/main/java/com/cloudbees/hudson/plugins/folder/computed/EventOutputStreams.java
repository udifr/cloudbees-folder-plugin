/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.cloudbees.hudson.plugins.folder.computed;

import com.cloudbees.hudson.plugins.folder.Folder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.IOUtils;

/**
 * An factory for {@link OutputStream} instances that can concurrently write to the same file and do lots of other
 * wonderful magical things (excluding changing the baby's nappy).
 * <ul>
 * <li>Output is collected in batches, one batch per thread and flushed periodically.</li>
 * <li>The file is only opened when actually writing.</li>
 * <li>The file is rotated if it gets too big.</li>
 * <li>You can close it over and over, it will auto re-open on next write!</li>
 * </ul>
 * The primary intended use case is for event processing with {@link FolderComputation#createEventsListener()}
 * where there may be multiple concurrent events in flight and we need to:
 * <ul>
 * <li>Try to keep each events log messages close together, hence the batching</li>
 * <li>Rotate after it gets too big</li>
 * <li>Not hold the file open indefinitely - ideally only when writing so that it can be moved by other processes</li>
 * </ul>
 * @since 5.18
 */
public class EventOutputStreams implements Closeable {
    /**
     * The queue of pending output.
     */
    private final BlockingQueue<byte[]> pending = new LinkedBlockingQueue<byte[]>();
    /**
     * The amount of data in the pending queue.
     */
    private final AtomicInteger pendingSize = new AtomicInteger();
    /**
     * The primary file name.
     */
    private final OutputFile outputFile;
    /**
     * How often to trigger a flush (defaults to 250ms)
     */
    private final long flushIntervalNanos;
    /**
     * How big to trigger a flush (defaults to 1024)
     */
    private final int flushSize;
    /**
     * How big to trigger a rotation (defaults to 32k)
     */
    private final long rotateSize;
    /**
     * How many rotated files to keep.
     */
    private final int fileCount;
    /**
     * Flag to indicate that the file should be appended on next open.
     */
    private boolean appendNextOpen;
    /**
     * The value of {@link System#nanoTime()} the last time the stream was flushed.
     */
    private volatile long lastFlushNanos;
    /**
     * The lock to hold when actually writing to the file.
     */
    private final Object writeLock = new Object();

    public EventOutputStreams(OutputFile outputFile, boolean append) {
        this(outputFile, append, 0);
    }

    public EventOutputStreams(OutputFile outputFile, boolean append, int fileCount) {
        this(outputFile, 250, TimeUnit.MILLISECONDS, 1024, append, 32 * 1024L, fileCount);
    }

    public EventOutputStreams(OutputFile outputFile,
                              long flushInterval,
                              TimeUnit flushIntervalUnits,
                              int flushSize,
                              boolean append,
                              long rotateSize,
                              int fileCount) {
        this.outputFile = outputFile;
        this.flushIntervalNanos = flushIntervalUnits.toNanos(flushInterval);
        this.flushSize = flushSize;
        this.fileCount = fileCount;
        this.rotateSize = rotateSize;
        this.appendNextOpen = append;
        this.lastFlushNanos = System.nanoTime();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    private void offer(byte[] content) throws IOException {
        int pendingSize;
        if (content == null) {
            pendingSize = this.pendingSize.get();
        } else {
            if (!pending.offer(content)) {
                throw new IOException("buffer full");
            }
            pendingSize = this.pendingSize.addAndGet(content.length);
        }
        if (content == null || pendingSize >= flushSize || System.nanoTime() - lastFlushNanos > flushIntervalNanos) {
            synchronized (writeLock) {
                File file = outputFile.get();
                if (!appendNextOpen || file.length() > rotateSize) {
                    if (fileCount > 0) {
                        for (int i = fileCount - 1; i >= 0; i--) {
                            File f = i == 0
                                    ? file
                                    : new File(file.getParent(), file.getName() + "." + (i));
                            if (f.exists()) {
                                File n = new File(file.getParent(), file.getName() + "." + (i + 1));
                                n.delete();
                                f.renameTo(n);
                            }
                        }
                    } else {
                        appendNextOpen = false;
                    }
                }
                FileOutputStream os = null;
                try {
                    os = new FileOutputStream(file, appendNextOpen);
                    byte[] bytes;
                    while (null != (bytes = pending.poll())) {
                        this.pendingSize.addAndGet(-bytes.length);
                        os.write(bytes);
                    }
                } catch (IOException e) {
                    // ignore
                } finally {
                    appendNextOpen = true;
                    IOUtils.closeQuietly(os);
                }
            }
        }
    }

    /**
     * Gets a new {@link OutputStream}, the caller must close the stream in order to ensure all its output gets written.
     * @return a new {@link OutputStream}.
     */
    public OutputStream get() {
        return new OutputStream() {
            private final byte[] buf = new byte[1024];
            private int last = 0;
            private int index = 0;
            private long start = System.nanoTime();

            @Override
            public void write(int b) throws IOException {
                buf[index] = (byte) b;
                index++;
                if (b == '\n') {
                    last = index;
                }
                lazyFlush();
            }

            private void lazyFlush() throws IOException {
                long end = System.nanoTime();
                if (index >= buf.length || end - start > flushIntervalNanos) {
                    start = end;
                    if (index == 0) {
                        return;
                    }
                    if (last == 0 || index == last) { // it's a long line, send the partial
                        offer(buf.clone());
                        index = 0;
                    } else { // send up to the last newline
                        offer(Arrays.copyOf(buf, last));
                        System.arraycopy(buf, last, buf, 0, index - last);
                        index -= last;
                    }
                    last = 0;
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (index + len <= buf.length) {
                    System.arraycopy(b, off, buf, index, len);
                    for (int i = index + len - 1; i >= index; i--) {
                        if (buf[i] == '\n') {
                            last = i + 1;
                            break;
                        }
                    }
                    index += len;
                    lazyFlush();
                } else {
                    while (len > 0) {
                        int l = Math.min(len, buf.length - index);
                        write(b, off, l);
                        off += l;
                        len -= l;
                    }
                }
            }

            @Override
            public void flush() throws IOException {
                start = System.nanoTime();
                if (index > 0) {
                    offer(Arrays.copyOf(buf, index));
                    index = 0;
                    last = 0;
                }
            }

            @Override
            public void close() throws IOException {
                flush();
                offer(null); // force a flush to disk
            }
        };
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        offer(null);
    }

    /**
     * Supplies the current output file destination. We use indirection so that when used from a
     * {@link FolderComputation} the containing {@link Folder} can be moved without keeping the events log open.
     */
    public interface OutputFile {
        /**
         * Returns the file that output is being sent to.
         *
         * @return the file that output is being sent to.
         */
        @NonNull
        File get();
    }
}
