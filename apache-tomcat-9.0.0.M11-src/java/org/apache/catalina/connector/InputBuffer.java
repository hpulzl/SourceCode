/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.connector;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ReadListener;

import org.apache.catalina.security.SecurityUtil;
import org.apache.coyote.ActionCode;
import org.apache.coyote.ContainerThreadMarker;
import org.apache.coyote.Request;
import org.apache.tomcat.util.buf.B2CConverter;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.res.StringManager;

/**
 * The buffer used by Tomcat request. This is a derivative of the Tomcat 3.3
 * OutputBuffer, adapted to handle input instead of output. This allows
 * complete recycling of the facade objects (the ServletInputStream and the
 * BufferedReader).
 *
 * @author Remy Maucherat
 */
public class InputBuffer extends Reader
    implements ByteChunk.ByteInputChannel, CharChunk.CharInputChannel,
               CharChunk.CharOutputChannel, ApplicationBufferHandler {

    /**
     * The string manager for this package.
     */
    protected static final StringManager sm = StringManager.getManager(InputBuffer.class);

    public static final int DEFAULT_BUFFER_SIZE = 8 * 1024;

    // The buffer can be used for byte[] and char[] reading
    // ( this is needed to support ServletInputStream and BufferedReader )
    public final int INITIAL_STATE = 0;
    public final int CHAR_STATE = 1;
    public final int BYTE_STATE = 2;


    /**
     * Encoder cache.
     */
    private static final Map<Charset, SynchronizedStack<B2CConverter>> encoders = new ConcurrentHashMap<>();

    // ----------------------------------------------------- Instance Variables

    /**
     * The byte buffer.
     */
    private final ByteChunk bb;
    private ByteBuffer tempRead;


    /**
     * The chunk buffer.
     */
    private CharChunk cb;


    /**
     * State of the output buffer.
     */
    private int state = 0;


    /**
     * Flag which indicates if the input buffer is closed.
     */
    private boolean closed = false;


    /**
     * Encoding to use.
     */
    private String enc;


    /**
     * Current byte to char converter.
     */
    protected B2CConverter conv;


    /**
     * Associated Coyote request.
     */
    private Request coyoteRequest;


    /**
     * Buffer position.
     */
    private int markPos = -1;


    /**
     * Buffer size.
     */
    private final int size;


    // ----------------------------------------------------------- Constructors


    /**
     * Default constructor. Allocate the buffer with the default buffer size.
     */
    public InputBuffer() {

        this(DEFAULT_BUFFER_SIZE);

    }


    /**
     * Alternate constructor which allows specifying the initial buffer size.
     *
     * @param size Buffer size to use
     */
    public InputBuffer(int size) {

        this.size = size;
        tempRead = ByteBuffer.allocate(size);
        tempRead.flip();
        bb = new ByteChunk(size);
        bb.setLimit(size);
        bb.setByteInputChannel(this);
        cb = new CharChunk(size);
        cb.setLimit(size);
        cb.setOptimizedWrite(false);
        cb.setCharInputChannel(this);
        cb.setCharOutputChannel(this);

    }


    // ------------------------------------------------------------- Properties


    /**
     * Associated Coyote request.
     *
     * @param coyoteRequest Associated Coyote request
     */
    public void setRequest(Request coyoteRequest) {
        this.coyoteRequest = coyoteRequest;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Recycle the output buffer.
     */
    public void recycle() {

        state = INITIAL_STATE;

        // If usage of mark made the buffer too big, reallocate it
        if (cb.getChars().length > size) {
            cb = new CharChunk(size);
            cb.setLimit(size);
            cb.setOptimizedWrite(false);
            cb.setCharInputChannel(this);
            cb.setCharOutputChannel(this);
        } else {
            cb.recycle();
        }
        markPos = -1;
        bb.recycle();
        closed = false;

        if (conv != null) {
            conv.recycle();
            encoders.get(conv.getCharset()).push(conv);
            conv = null;
        }

        enc = null;
    }


    /**
     * Close the input buffer.
     *
     * @throws IOException An underlying IOException occurred
     */
    @Override
    public void close() throws IOException {
        closed = true;
    }


    public int available() {
        int available = 0;
        if (state == BYTE_STATE) {
            available = bb.getLength();
        } else if (state == CHAR_STATE) {
            available = cb.getLength();
        }
        if (available == 0) {
            coyoteRequest.action(ActionCode.AVAILABLE,
                    Boolean.valueOf(coyoteRequest.getReadListener() != null));
            available = (coyoteRequest.getAvailable() > 0) ? 1 : 0;
        }
        return available;
    }


    public void setReadListener(ReadListener listener) {
        coyoteRequest.setReadListener(listener);

        // The container is responsible for the first call to
        // listener.onDataAvailable(). If isReady() returns true, the container
        // needs to call listener.onDataAvailable() from a new thread. If
        // isReady() returns false, the socket will be registered for read and
        // the container will call listener.onDataAvailable() once data arrives.
        // Must call isFinished() first as a call to isReady() if the request
        // has been finished will register the socket for read interest and that
        // is not required.
        if (!coyoteRequest.isFinished() && isReady()) {
            coyoteRequest.action(ActionCode.DISPATCH_READ, null);
            if (!ContainerThreadMarker.isContainerThread()) {
                // Not on a container thread so need to execute the dispatch
                coyoteRequest.action(ActionCode.DISPATCH_EXECUTE, null);
            }
        }
    }


    public boolean isFinished() {
        int available = 0;
        if (state == BYTE_STATE) {
            available = bb.getLength();
        } else if (state == CHAR_STATE) {
            available = cb.getLength();
        }
        if (available > 0) {
            return false;
        } else {
            return coyoteRequest.isFinished();
        }
    }


    public boolean isReady() {
        if (coyoteRequest.getReadListener() == null) {
            throw new IllegalStateException(sm.getString("inputBuffer.requiresNonBlocking"));
        }
        if (isFinished()) {
            // If this is a non-container thread, need to trigger a read
            // which will eventually lead to a call to onAllDataRead() via a
            // container thread.
            if (!ContainerThreadMarker.isContainerThread()) {
                coyoteRequest.action(ActionCode.DISPATCH_READ, null);
                coyoteRequest.action(ActionCode.DISPATCH_EXECUTE, null);
            }
            return false;
        }
        boolean result = available() > 0;
        if (!result) {
            coyoteRequest.action(ActionCode.NB_READ_INTEREST, null);
        }
        return result;
    }


    boolean isBlocking() {
        return coyoteRequest.getReadListener() == null;
    }


    // ------------------------------------------------- Bytes Handling Methods

    /**
     * Reads new bytes in the byte chunk.
     *
     * @throws IOException An underlying IOException occurred
     */
    @Override
    public int realReadBytes() throws IOException {
        if (closed) {
            return -1;
        }
        if (coyoteRequest == null) {
            return -1;
        }

        if (state == INITIAL_STATE) {
            state = BYTE_STATE;
        }

        int result = coyoteRequest.doRead(this);
        bb.setBytes(tempRead.array(), tempRead.arrayOffset() + tempRead.position(),
                tempRead.remaining());
        tempRead.position(0).limit(0);
        return result;
    }


    public int readByte() throws IOException {
        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        return bb.substract();
    }


    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        return bb.substract(b, off, len);
    }


    /**
     * Transfers bytes from the buffer to the specified ByteBuffer. After the
     * operation the position of the ByteBuffer will be returned to the one
     * before the operation, the limit will be the position incremented by
     * the number of the transfered bytes.
     *
     * @param b the ByteBuffer into which bytes are to be written.
     * @return an integer specifying the actual number of bytes read, or -1 if
     *         the end of the stream is reached
     * @throws IOException if an input or output exception has occurred
     */
    public int read(ByteBuffer b) throws IOException {
        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        return bb.substract(b);
    }


    // ------------------------------------------------- Chars Handling Methods


    /**
     * Since the converter will use append, it is possible to get chars to
     * be removed from the buffer for "writing". Since the chars have already
     * been read before, they are ignored. If a mark was set, then the
     * mark is lost.
     */
    @Override
    public void realWriteChars(char c[], int off, int len) throws IOException {
        markPos = -1;
        cb.setOffset(0);
        cb.setEnd(0);
    }


    public void setEncoding(String s) {
        enc = s;
    }


    @Override
    public int realReadChars() throws IOException {
        checkConverter();

        boolean eof = false;

        if (bb.getLength() <= 0) {
            int nRead = realReadBytes();
            if (nRead < 0) {
                eof = true;
            }
        }

        if (markPos == -1) {
            cb.setOffset(0);
            cb.setEnd(0);
        } else {
            // Make sure there's enough space in the worst case
            cb.makeSpace(bb.getLength());
            if ((cb.getBuffer().length - cb.getEnd()) == 0 && bb.getLength() != 0) {
                // We went over the limit
                cb.setOffset(0);
                cb.setEnd(0);
                markPos = -1;
            }
        }

        state = CHAR_STATE;
        conv.convert(bb, cb, eof);

        if (cb.getLength() == 0 && eof) {
            return -1;
        } else {
            return cb.getLength();
        }
    }


    @Override
    public int read() throws IOException {

        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        return cb.substract();
    }


    @Override
    public int read(char[] cbuf) throws IOException {

        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        return read(cbuf, 0, cbuf.length);
    }


    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {

        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        return cb.substract(cbuf, off, len);
    }


    @Override
    public long skip(long n) throws IOException {
        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        if (n < 0) {
            throw new IllegalArgumentException();
        }

        long nRead = 0;
        while (nRead < n) {
            if (cb.getLength() >= n) {
                cb.setOffset(cb.getStart() + (int) n);
                nRead = n;
            } else {
                nRead += cb.getLength();
                cb.setOffset(cb.getEnd());
                int nb = realReadChars();
                if (nb < 0) {
                    break;
                }
            }
        }
        return nRead;
    }


    @Override
    public boolean ready() throws IOException {
        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }
        if (state == INITIAL_STATE) {
            state = CHAR_STATE;
        }
        return (available() > 0);
    }


    @Override
    public boolean markSupported() {
        return true;
    }


    @Override
    public void mark(int readAheadLimit) throws IOException {

        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        if (cb.getLength() <= 0) {
            cb.setOffset(0);
            cb.setEnd(0);
        } else {
            if ((cb.getBuffer().length > (2 * size)) && (cb.getLength()) < (cb.getStart())) {
                System.arraycopy(cb.getBuffer(), cb.getStart(), cb.getBuffer(), 0, cb.getLength());
                cb.setEnd(cb.getLength());
                cb.setOffset(0);
            }
        }
        cb.setLimit(cb.getStart() + readAheadLimit + size);
        markPos = cb.getStart();
    }


    @Override
    public void reset() throws IOException {

        if (closed) {
            throw new IOException(sm.getString("inputBuffer.streamClosed"));
        }

        if (state == CHAR_STATE) {
            if (markPos < 0) {
                cb.recycle();
                markPos = -1;
                throw new IOException();
            } else {
                cb.setOffset(markPos);
            }
        } else {
            bb.recycle();
        }
    }


    public void checkConverter() throws IOException {
        if (conv == null) {
            setConverter();
        }
    }


    private void setConverter() throws IOException {
        if (coyoteRequest != null) {
            enc = coyoteRequest.getCharacterEncoding();
        }

        if (enc == null) {
            enc = org.apache.coyote.Constants.DEFAULT_CHARACTER_ENCODING;
        }

        Charset charset = B2CConverter.getCharset(enc);
        SynchronizedStack<B2CConverter> stack = encoders.get(charset);
        if (stack == null) {
            stack = new SynchronizedStack<>();
            encoders.putIfAbsent(charset, stack);
            stack = encoders.get(charset);
        }
        conv = stack.pop();

        if (conv == null) {
            conv = createConverter(charset);
        }
    }


    private static B2CConverter createConverter(Charset charset) throws IOException {
        if (SecurityUtil.isPackageProtectionEnabled()) {
            try {
                return AccessController.doPrivileged(new PrivilegedExceptionAction<B2CConverter>() {

                    @Override
                    public B2CConverter run() throws IOException {
                        return new B2CConverter(charset);
                    }
                });
            } catch (PrivilegedActionException ex) {
                Exception e = ex.getException();
                if (e instanceof IOException) {
                    throw (IOException) e;
                } else {
                    throw new IOException(e);
                }
            }
        } else {
            return new B2CConverter(charset);
        }

    }


    @Override
    public void setByteBuffer(ByteBuffer buffer) {
        tempRead = buffer;
    }


    @Override
    public ByteBuffer getByteBuffer() {
        return tempRead;
    }


    @Override
    public void expand(int size) {
        // no-op
    }
}
