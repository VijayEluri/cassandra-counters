/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cassandra.io.sstable;

import java.io.*;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.AbstractCompactedRow;
import org.apache.cassandra.io.util.BufferedRandomAccessFile;
import org.apache.cassandra.io.util.SegmentedFile;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.io.sstable.IRecoveryProcessor;
import org.apache.cassandra.utils.BloomFilter;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.EstimatedHistogram;

public class SSTableWriter extends SSTable
{
    private static Logger logger = LoggerFactory.getLogger(SSTableWriter.class);

    private IndexWriter iwriter;
    private SegmentedFile.Builder dbuilder;
    private final BufferedRandomAccessFile dataFile;
    private DecoratedKey lastWrittenKey;

    public SSTableWriter(String filename, long keyCount) throws IOException
    {
        this(filename, keyCount, DatabaseDescriptor.getCFMetaData(Descriptor.fromFilename(filename)), StorageService.getPartitioner());
    }

    public SSTableWriter(String filename, long keyCount, CFMetaData metadata, IPartitioner partitioner) throws IOException
    {
        super(Descriptor.fromFilename(filename), metadata, partitioner);
        iwriter = new IndexWriter(desc, partitioner, keyCount);
        dbuilder = SegmentedFile.getBuilder(DatabaseDescriptor.getDiskAccessMode());
        dataFile = new BufferedRandomAccessFile(getFilename(), "rw", DatabaseDescriptor.getInMemoryCompactionLimit());

        // the set of required components
        components.add(Component.DATA);
        components.add(Component.FILTER);
        components.add(Component.PRIMARY_INDEX);
        components.add(Component.STATS);
    }

    private long beforeAppend(DecoratedKey decoratedKey) throws IOException
    {
        if (decoratedKey == null)
        {
            throw new IOException("Keys must not be null.");
        }
        if (lastWrittenKey != null && lastWrittenKey.compareTo(decoratedKey) > 0)
        {
            logger.info("Last written key : " + lastWrittenKey);
            logger.info("Current key : " + decoratedKey);
            logger.info("Writing into file " + getFilename());
            throw new IOException("Keys must be written in ascending order.");
        }
        return (lastWrittenKey == null) ? 0 : dataFile.getFilePointer();
    }

    private void afterAppend(DecoratedKey decoratedKey, long dataPosition) throws IOException
    {
        lastWrittenKey = decoratedKey;

        if (logger.isTraceEnabled())
            logger.trace("wrote " + decoratedKey + " at " + dataPosition);
        dbuilder.addPotentialBoundary(dataPosition);
        iwriter.afterAppend(decoratedKey, dataPosition);
    }

    public void append(AbstractCompactedRow row) throws IOException
    {
        long currentPosition = beforeAppend(row.key);
        FBUtilities.writeShortByteArray(row.key.key, dataFile);
        row.write(dataFile);
        estimatedRowSize.add(dataFile.getFilePointer() - currentPosition);
        estimatedColumnCount.add(row.columnCount());
        afterAppend(row.key, currentPosition);
    }

    public void append(DecoratedKey decoratedKey, ColumnFamily cf) throws IOException
    {
        long startPosition = beforeAppend(decoratedKey);
        FBUtilities.writeShortByteArray(decoratedKey.key, dataFile);
        // write placeholder for the row size, since we don't know it yet
        long sizePosition = dataFile.getFilePointer();
        dataFile.writeLong(-1);
        // write out row data
        int columnCount = ColumnFamily.serializer().serializeWithIndexes(cf, dataFile);
        // seek back and write the row size (not including the size Long itself)
        long endPosition = dataFile.getFilePointer();
        dataFile.seek(sizePosition);
        dataFile.writeLong(endPosition - (sizePosition + 8));
        // finally, reset for next row
        dataFile.seek(endPosition);
        afterAppend(decoratedKey, startPosition);
        estimatedRowSize.add(endPosition - startPosition);
        estimatedColumnCount.add(columnCount);
    }

    public void append(DecoratedKey decoratedKey, byte[] value) throws IOException
    {
        long currentPosition = beforeAppend(decoratedKey);
        FBUtilities.writeShortByteArray(decoratedKey.key, dataFile);
        assert value.length > 0;
        dataFile.writeLong(value.length);
        dataFile.write(value);
        afterAppend(decoratedKey, currentPosition);
    }

    public SSTableReader closeAndOpenReader() throws IOException
    {
        return closeAndOpenReader(System.currentTimeMillis());
    }

    public SSTableReader closeAndOpenReader(long maxDataAge) throws IOException
    {
        // index and filter
        iwriter.close();

        // main data
        dataFile.close(); // calls force

        // write sstable statistics
        writeStatistics(desc);

        // remove the 'tmp' marker from all components
        final Descriptor newdesc = rename(desc, components);


        // finalize in-memory state for the reader
        SegmentedFile ifile = iwriter.builder.complete(newdesc.filenameFor(SSTable.COMPONENT_INDEX));
        SegmentedFile dfile = dbuilder.complete(newdesc.filenameFor(SSTable.COMPONENT_DATA));
        SSTableReader sstable = SSTableReader.internalOpen(newdesc, components, metadata, partitioner, ifile, dfile, iwriter.summary, iwriter.bf, maxDataAge, estimatedRowSize, estimatedColumnCount);
        iwriter = null;
        dbuilder = null;
        return sstable;
    }

    private void writeStatistics(Descriptor desc) throws IOException
    {
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(desc.filenameFor(SSTable.COMPONENT_STATS)));
        EstimatedHistogram.serializer.serialize(estimatedRowSize, dos);
        EstimatedHistogram.serializer.serialize(estimatedColumnCount, dos);
        dos.close();
    }

    static Descriptor rename(Descriptor tmpdesc, Set<Component> components)
    {
        Descriptor newdesc = tmpdesc.asTemporary(false);
        try
        {
            for (Component component : components)
                FBUtilities.renameWithConfirm(tmpdesc.filenameFor(component), newdesc.filenameFor(component));
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
        return newdesc;
    }

    public long getFilePointer()
    {
        return dataFile.getFilePointer();
    }
    
    /**
     * @return An estimate of the number of keys contained in the given data file.
     */
    protected static long estimateRows(Descriptor desc, BufferedRandomAccessFile dfile) throws IOException
    {
        // collect sizes for the first 1000 keys, or first 100 megabytes of data
        final int SAMPLES_CAP = 1000, BYTES_CAP = (int)Math.min(100000000, dfile.length());
        int keys = 0;
        long dataPosition = 0;
        while (dataPosition < BYTES_CAP && keys < SAMPLES_CAP)
        {
            dfile.seek(dataPosition);
            FBUtilities.readShortByteArray(dfile);
            long dataSize = SSTableReader.readRowSize(dfile, desc);
            dataPosition = dfile.getFilePointer() + dataSize;
            keys++;
        }
        dfile.seek(0);
        return dfile.length() / (dataPosition / keys);
    }

    /**
     * Removes the given SSTable from temporary status and opens it, rebuilding the non-essential portions of the
     * file if necessary.
     */
    public static SSTableReader recoverAndOpen(Descriptor desc, IRecoveryProcessor rp) throws IOException
    {
        if (!desc.isLatestVersion)
            // TODO: streaming between different versions will fail: need support for
            // recovering other versions to provide a stable streaming api
            throw new RuntimeException(String.format("Cannot recover SSTable with version %s (current version %s).",
                                                     desc.version, Descriptor.CURRENT_VERSION));
 
        // check that both index and filter files are present
        logger.debug("In recoverAndOpen with Descriptor {}", desc);
        File ifile = new File(desc.filenameFor(SSTable.COMPONENT_INDEX));
        File ffile = new File(desc.filenameFor(SSTable.COMPONENT_FILTER));
        if (ifile.exists() && ffile.exists()) {
            // nothing to do
            return SSTableReader.open(rename(desc, SSTable.componentsFor(desc)));
        }

        // remove existing files
        ifile.delete();
        ffile.delete();
        
        // recover sstable
        rp.recover(desc);

        // FIXME: once maybeRecover is recovering BMIs, it should return the recovered
        // components
        return SSTableReader.open(desc.asTemporary(false));
    }

    /**
     * Encapsulates writing the index and filter for an SSTable. The state of this object is not valid until it has been closed.
     */
    protected static class IndexWriter
    {
        private final BufferedRandomAccessFile indexFile;
        public final Descriptor desc;
        public final IPartitioner partitioner;
        public final SegmentedFile.Builder builder;
        public final IndexSummary summary;
        public final BloomFilter bf;
        
        IndexWriter(Descriptor desc, IPartitioner part, long keyCount) throws IOException
        {
            this.desc = desc;
            this.partitioner = part;
            indexFile = new BufferedRandomAccessFile(desc.filenameFor(SSTable.COMPONENT_INDEX), "rw", 8 * 1024 * 1024);
            builder = SegmentedFile.getBuilder(DatabaseDescriptor.getIndexAccessMode());
            summary = new IndexSummary();
            bf = BloomFilter.getFilter(keyCount, 15);
        }

        public void afterAppend(DecoratedKey key, long dataPosition) throws IOException
        {
            bf.add(key.key);
            long indexPosition = indexFile.getFilePointer();
            FBUtilities.writeShortByteArray(key.key, indexFile);
            indexFile.writeLong(dataPosition);
            if (logger.isTraceEnabled())
                logger.trace("wrote index of " + key + " at " + indexPosition);

            summary.maybeAddEntry(key, indexPosition);
            builder.addPotentialBoundary(indexPosition);
        }

        /**
         * Closes the index and bloomfilter, making the public state of this writer valid for consumption.
         */
        public void close() throws IOException
        {
            // bloom filter
            FileOutputStream fos = new FileOutputStream(desc.filenameFor(SSTable.COMPONENT_FILTER));
            DataOutputStream stream = new DataOutputStream(fos);
            BloomFilter.serializer().serialize(bf, stream);
            stream.flush();
            fos.getFD().sync();
            stream.close();

            // index
            indexFile.getChannel().force(true);
            indexFile.close();

            // finalize in-memory index state
            summary.complete();
        }
    }
}
