package com.hadoop.compression.snappy;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.io.compress.Decompressor;
import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyException;

public class SnappyDecompressor implements Decompressor
{

    private static final Log logger                     = LogFactory.getLog(SnappyDecompressor.class.getName());

    private boolean          finished;
    private ByteBuffer       outBuf;
    private ByteBuffer       uncompressedBuf;

    private long             bytesRead                  = 0L;
    private long             bytesWritten               = 0L;


    public SnappyDecompressor(int bufferSize)
    {
       outBuf          = ByteBuffer.allocateDirect(bufferSize);
       uncompressedBuf = ByteBuffer.allocateDirect(bufferSize);

       reset();
    }


    public synchronized void setInput(byte[] b, int off, int len)
    {
        if (b == null)
        {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || off > b.length - len)
        {
            throw new ArrayIndexOutOfBoundsException();
        }

        finished = false;

        outBuf.put(b, off, len);

        bytesRead += len;
    }

    public synchronized void setDictionary(byte[] b, int off, int len)
    {
        // do nothing
    }

    public synchronized boolean needsInput()
    {
        //needs input if the uncompressed data was consumed
        if (uncompressedBuf.position() > 0 && uncompressedBuf.limit() > uncompressedBuf.position())
            return false;

        return true;
    }

    public synchronized boolean needsDictionary()
    {
        return false;
    }

    public synchronized boolean finished()
    {
        return finished;
    }

    public synchronized int decompress(byte[] b, int off, int len) throws IOException
    {


        if (b == null)
        {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || off > b.length - len)
        {
            throw new ArrayIndexOutOfBoundsException();
        }

        //nothing to decompress
        if ((outBuf.position() == 0 && uncompressedBuf.position() == 0)   || finished)
        {
            reset();
            finished = true;

            return 0;
        }

        //only needs to do this once per input
        if(uncompressedBuf.position() == 0)
        {
            outBuf.limit(outBuf.position());
            outBuf.rewind();

            int neededLen = Snappy.uncompressedLength(outBuf);
            outBuf.rewind();

            if(neededLen > uncompressedBuf.capacity())
                uncompressedBuf = ByteBuffer.allocateDirect(neededLen);

            int lim = Snappy.uncompress(outBuf, uncompressedBuf);

            uncompressedBuf.limit(lim);
            uncompressedBuf.rewind();
        }

        int n = (uncompressedBuf.limit() - uncompressedBuf.position()) > len ? len : (uncompressedBuf.limit() - uncompressedBuf.position());

        if(n == 0)
        {
            reset();
            finished = true;
            return 0;
        }

        uncompressedBuf.get(b, off, n);

        bytesWritten += n;

        // Set 'finished' if snappy has consumed all user-data
        if (uncompressedBuf.position() == uncompressedBuf.limit())
        {
            reset();
            finished = true;
        }

        return n;
    }

    public synchronized int getRemaining()
    {
        // Never use this function in BlockDecompressorStream.
        return 0;
    }

    public synchronized void reset()
    {
        finished = false;

        uncompressedBuf.limit(uncompressedBuf.capacity());
        uncompressedBuf.rewind();

        outBuf.limit(outBuf.capacity());
        outBuf.rewind();

        bytesRead = bytesWritten = 0L;
    }

    public synchronized void end()
    {
        // do nothing
    }

    protected void finalize()
    {
        end();
    }

}
