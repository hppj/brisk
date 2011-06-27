/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.hadoop.fs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.EmbeddedServer;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSOutputSummer;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.FileOutputCommitter;
import org.apache.thrift.transport.TTransportException;

public class CassandraFileSystemTest extends CleanupHelper
{
    /**
     * Set embedded cassandra up and spawn it in a new thread.
     *
     * @throws TTransportException
     * @throws IOException
     * @throws InterruptedException
     */
    @BeforeClass
    public static void setup() throws TTransportException, IOException, InterruptedException, ConfigurationException
    {
        EmbeddedServer.startBrisk();                
    }

    
    @Test
    public void testFileSystemWithoutFlush() throws Exception
    {
        testFileSystem(false);
    }
    
    @Test
    public void testFileSystemWithFlush() throws Exception
    {
        testFileSystem(true);
    }
    
    private void testFileSystem(boolean flush) throws Exception
    {
        CassandraFileSystem fs = new CassandraFileSystem();
        fs.initialize(URI.create("cfs://localhost:"+DatabaseDescriptor.getRpcPort()+"/"), new Configuration());

        fs.mkdirs(new Path("/mytestdir"));
        fs.mkdirs(new Path("/mytestdir/sub1"));
        fs.mkdirs(new Path("/mytestdir/sub2"));
        fs.mkdirs(new Path("/mytestdir/sub3"));
        fs.mkdirs(new Path("/mytestdir/sub3/sub4"));

        //Create a 1MB file to sent to fs
        File tmp = File.createTempFile("testcfs", "input");

        Writer writer = new FileWriter(tmp);

        char buf[] = new char[1024];

        fillArray(buf);

        for(int i=0; i<1024; i++)
            writer.write(buf);

        writer.close();
        
        tmp.deleteOnExit();

        //Write file
        fs.copyFromLocalFile(new Path("file://"+tmp.getAbsolutePath()), new Path("/mytestdir/testfile"));
        
        if(flush)
        {
            List<Future<?>> cb = Table.open("cfs").flush();
            
            for(Future c : cb)
                c.get();
        }

        Set<Path> allPaths = fs.store.listDeepSubPaths(new Path("/mytestdir"));

        //Verify deep paths
        assertEquals(5, allPaths.size());

        //verify shallow path
        Set<Path> thisPath = fs.store.listSubPaths(new Path("/mytestdir"));
        assertEquals(4, thisPath.size());

        //Check file status
        FileStatus stat = fs.getFileStatus(new Path("/mytestdir/testfile"));

        assertEquals(tmp.getAbsoluteFile().length(), stat.getLen());
        assertEquals(false, stat.isDir());

        //Check block info
        BlockLocation[] info = fs.getFileBlockLocations(stat, 0, stat.getLen());
        assertEquals(1, info.length);
        assertEquals(FBUtilities.getLocalAddress().getHostName(), info[0].getHosts()[0]);

        info = fs.getFileBlockLocations(stat, 1, 10);
        assertTrue(info.length == 1);

        info = fs.getFileBlockLocations(stat, 0, 200);
        assertTrue(info.length == 1);

        //Check dir status
        stat = fs.getFileStatus(new Path("/mytestdir"));
        assertEquals(true, stat.isDir());

        //Read back the file
        File out = File.createTempFile("testcfs", "output");

        fs.copyToLocalFile(new Path("/mytestdir/testfile"), new Path("file://"+ out.getAbsolutePath()));

        Reader reader = new FileReader(out);
        for(int i=0; i<1024; i++)
        {
           assertEquals(1024, reader.read(buf));
        }

        assertEquals(-1,reader.read());
        reader.close();
        out.deleteOnExit();
        
        // Verify the digests
        assertDigest(tmp, out);
    }
    
    @Test
    public void testFileSystemSeek() throws Exception
    {
        CassandraFileSystem fs = new CassandraFileSystem();
        fs.initialize(URI.create("cfs://localhost:"+DatabaseDescriptor.getRpcPort()+"/"), new Configuration());

        // Create the test directory
        fs.mkdirs(new Path("/mytestdir2"));
        Path path = new Path("/mytestdir2/test");
        
        // Create the test file to write sample data to.
        FSDataOutputStream out = fs.create(path);

        for(int i=0; i<500; i++)
            out.writeInt(i);

        out.close();

        // Let's try to open it back and read its data.
        FSDataInputStream in = fs.open(path);

        in.seek(200); // 200 / 4 = skip 50 integers. Still have 450 to read.
        
        for (int i = 50; i < 500; i++)
        {
            int res = in.readInt();
            assertEquals(i, res);
        }
        
        in.close();
    }
    
    @Test
    public void testFileSystemMixedContent() throws Exception
    {
        CassandraFileSystem fs = new CassandraFileSystem();
        fs.initialize(URI.create("cfs://localhost:"+DatabaseDescriptor.getRpcPort()+"/"), new Configuration());
        
        // Create the test directory
        fs.mkdirs(new Path("/mytestdir3"));
        Path path = new Path("/mytestdir3/test");
        
        // Create the test file to write sample data to.
        FSDataOutputStream out = fs.create(path);
        
        File expected = File.createTempFile("testcfs3", "expected");
        DataOutputStream expectedOut = new DataOutputStream(new FileOutputStream(expected));
        
        // Note: Here we write to both, the Hadoop file and to a temp (used to compare their MD5)
        
        // Write some integers
        for(int i=0; i<2000; i++)
        {
            out.writeInt(i);
            expectedOut.writeInt(i);
        }
        
        // Write some UTF
        out.writeUTF("we are saving a string here");
        expectedOut.writeUTF("we are saving a string here");
        
        // Write some long values
        for(long l=0; l<20; l++)
        {    
            out.writeLong(l);
            expectedOut.writeLong(l);
        }
        
        out.close();
        
        // Let's try to open it back and read its data.
        FSDataInputStream in = fs.open(path);
     
        assertDigest(new FileInputStream(expected), in);
    }


	private void fillArray(char[] buf) {
		for (int j = 0; j < buf.length; j++) {
			buf[j] = (char) j;
		}
	}


	private void assertDigest(File srcFile, File outFile) throws Exception {
				
		InputStream srcFileIn = null;
		InputStream outFileIn = null;
		try 
		{
			srcFileIn = new BufferedInputStream(new FileInputStream(srcFile));
     		outFileIn = new BufferedInputStream(new FileInputStream(outFile));
			assertDigest(srcFileIn, outFileIn);			
		} finally {
			srcFileIn.close();
			outFileIn.close();
		}		
	}
	
	private void assertDigest(InputStream a1, InputStream a2) throws Exception {
	    MessageDigest md5 = MessageDigest.getInstance("MD5");
	    byte[] expected = Util.digestInputStream(md5, a1);
	    byte[] actual = Util.digestInputStream(md5, a2);
	    Assert.assertArrayEquals(expected, actual);
	}

}
