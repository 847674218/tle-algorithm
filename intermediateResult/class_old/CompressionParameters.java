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
package org.apache.cassandra.io.compress;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.AbstractSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.ConfigurationException;

public class CompressionParameters
{
    private static final Logger logger = LoggerFactory.getLogger(CompressionParameters.class);

    public final static int DEFAULT_CHUNK_LENGTH = 65536;
    public final static double DEFAULT_CRC_CHECK_CHANCE = 1.0;

    public static final String SSTABLE_COMPRESSION = "sstable_compression";
    public static final String CHUNK_LENGTH_KB = "chunk_length_kb";
    public static final String CRC_CHECK_CHANCE = "crc_check_chance";

    public static final Set<String> GLOBAL_OPTIONS = ImmutableSet.of(CRC_CHECK_CHANCE);

    public final ICompressor sstableCompressor;
    private final Integer chunkLength;
    private volatile double crcCheckChance;
    public final Map<String, String> otherOptions; // Unrecognized options, can be use by the compressor

    public static CompressionParameters create(Map<? extends CharSequence, ? extends CharSequence> opts) throws ConfigurationException
    {
        Map<String, String> options = copyOptions(opts);
        String sstableCompressionClass = options.get(SSTABLE_COMPRESSION);
        String chunkLength = options.get(CHUNK_LENGTH_KB);
        options.remove(SSTABLE_COMPRESSION);
        options.remove(CHUNK_LENGTH_KB);
        CompressionParameters cp = new CompressionParameters(sstableCompressionClass, parseChunkLength(chunkLength), options);
        cp.validate();
        return cp;
    }

    public CompressionParameters(String sstableCompressorClass, Integer chunkLength, Map<String, String> otherOptions) throws ConfigurationException
    {
        this(createCompressor(parseCompressorClass(sstableCompressorClass), otherOptions), chunkLength, otherOptions);
    }

    public CompressionParameters(ICompressor sstableCompressor)
    {
        // can't try/catch as first statement in the constructor, thus repeating constructor code here.
        this.sstableCompressor = sstableCompressor;
        chunkLength = null;
        otherOptions = Collections.emptyMap();
        crcCheckChance = DEFAULT_CRC_CHECK_CHANCE;
    }

    public CompressionParameters(ICompressor sstableCompressor, Integer chunkLength, Map<String, String> otherOptions) throws ConfigurationException
    {
        this.sstableCompressor = sstableCompressor;
        this.chunkLength = chunkLength;
        this.otherOptions = otherOptions;
        this.crcCheckChance = otherOptions.get(CRC_CHECK_CHANCE) == null
                              ? DEFAULT_CRC_CHECK_CHANCE
                              : parseCrcCheckChance(otherOptions.get(CRC_CHECK_CHANCE));
    }

    public void setCrcCheckChance(double crcCheckChance) throws ConfigurationException
    {
        validateCrcCheckChance(crcCheckChance);
        logger.debug("Setting crcCheckChance to {}", crcCheckChance);
        this.crcCheckChance = crcCheckChance;
    }

    public double getCrcCheckChance()
    {
        return this.crcCheckChance;
    }

    private static double parseCrcCheckChance(String crcCheckChance) throws ConfigurationException
    {
        try
        {
            double chance = Double.parseDouble(crcCheckChance);
            validateCrcCheckChance(chance);
            return chance;
        }
        catch (NumberFormatException e)
        {
            throw new ConfigurationException("crc_check_chance should be a double");
        }
    }

    private static void validateCrcCheckChance(double crcCheckChance) throws ConfigurationException
    {
        if (crcCheckChance < 0.0d || crcCheckChance > 1.0d)
            throw new ConfigurationException("crc_check_chance should be between 0.0 and 1.0");
    }

    public int chunkLength()
    {
        return chunkLength == null ? DEFAULT_CHUNK_LENGTH : chunkLength;
    }

    private static Class<? extends ICompressor> parseCompressorClass(String className) throws ConfigurationException
    {
        if (className == null || className.isEmpty())
            return null;

        className = className.contains(".") ? className : "org.apache.cassandra.io.compress." + className;
        try
        {
            return (Class<? extends ICompressor>)Class.forName(className);
        }
        catch (Exception e)
        {
            throw new ConfigurationException("Could not create Compression for type " + className, e);
        }
    }

    private static ICompressor createCompressor(Class<? extends ICompressor> compressorClass, Map<String, String> compressionOptions) throws ConfigurationException
    {
        if (compressorClass == null)
        {
            if (!compressionOptions.isEmpty())
                throw new ConfigurationException("Unknown compression options (" + compressionOptions.keySet() + ") since no compression class found");
            return null;
        }

        try
        {
            Method method = compressorClass.getMethod("create", Map.class);
            ICompressor compressor = (ICompressor)method.invoke(null, compressionOptions);
            // Check for unknown options
            AbstractSet<String> supportedOpts =  Sets.union(compressor.supportedOptions(), GLOBAL_OPTIONS);
            for (String provided : compressionOptions.keySet())
                if (!supportedOpts.contains(provided))
                    throw new ConfigurationException("Unknown compression options " + provided);
            return compressor;
        }
        catch (NoSuchMethodException e)
        {
            throw new ConfigurationException("create method not found", e);
        }
        catch (SecurityException e)
        {
            throw new ConfigurationException("Access forbiden", e);
        }
        catch (IllegalAccessException e)
        {
            throw new ConfigurationException("Cannot access method create in " + compressorClass.getName(), e);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            throw new ConfigurationException(String.format("%s.create() threw an error: %s",
                                             compressorClass.getSimpleName(),
                                             cause == null ? e.getClass().getName() + " " + e.getMessage() : cause.getClass().getName() + " " + cause.getMessage()),
                                             e);
        }
        catch (ExceptionInInitializerError e)
        {
            throw new ConfigurationException("Cannot initialize class " + compressorClass.getName());
        }
    }

    private static Map<String, String> copyOptions(Map<? extends CharSequence, ? extends CharSequence> co)
    {
        if (co == null || co.isEmpty())
            return Collections.<String, String>emptyMap();

        Map<String, String> compressionOptions = new HashMap<String, String>();
        for (Map.Entry<? extends CharSequence, ? extends CharSequence> entry : co.entrySet())
        {
            compressionOptions.put(entry.getKey().toString(), entry.getValue().toString());
        }
        return compressionOptions;
    }

    /**
     * Parse the chunk length (in KB) and returns it as bytes.
     */
    private static Integer parseChunkLength(String chLengthKB) throws ConfigurationException
    {
        if (chLengthKB == null)
            return null;

        try
        {
            int parsed = Integer.parseInt(chLengthKB);
            if (parsed > Integer.MAX_VALUE / 1024)
                throw new ConfigurationException("Value of " + CHUNK_LENGTH_KB + " is too large (" + parsed + ")");
            return 1024 * parsed;
        }
        catch (NumberFormatException e)
        {
            throw new ConfigurationException("Invalid value for " + CHUNK_LENGTH_KB, e);
        }
    }

    // chunkLength must be a power of 2 because we assume so when
    // computing the chunk number from an uncompressed file offset (see
    // CompressedRandomAccessReader.decompresseChunk())
    private void validate() throws ConfigurationException
    {
        // if chunk length was not set (chunkLength == null), this is fine, default will be used
        if (chunkLength != null)
        {
            if (chunkLength <= 0)
                throw new ConfigurationException("Invalid negative or null " + CHUNK_LENGTH_KB);

            int c = chunkLength;
            boolean found = false;
            while (c != 0)
            {
                if ((c & 0x01) != 0)
                {
                    if (found)
                        throw new ConfigurationException(CHUNK_LENGTH_KB + " must be a power of 2");
                    else
                        found = true;
                }
                c >>= 1;
            }
        }

        validateCrcCheckChance(crcCheckChance);
    }

    public Map<String, String> asThriftOptions()
    {
        Map<String, String> options = new HashMap<String, String>(otherOptions);
        if (sstableCompressor == null)
            return options;

        options.put(SSTABLE_COMPRESSION, sstableCompressor.getClass().getName());
        if (chunkLength != null)
            options.put(CHUNK_LENGTH_KB, chunkLengthInKB());
        return options;
    }

    private String chunkLengthInKB()
    {
        return String.valueOf(chunkLength() / 1024);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }
        else if (obj == null || obj.getClass() != getClass())
        {
            return false;
        }

        CompressionParameters cp = (CompressionParameters) obj;
        return new EqualsBuilder()
            .append(sstableCompressor, cp.sstableCompressor)
            .append(chunkLength, cp.chunkLength)
            .append(otherOptions, cp.otherOptions)
            .isEquals();
    }

    @Override
    public int hashCode()
    {
        return new HashCodeBuilder(29, 1597)
            .append(sstableCompressor)
            .append(chunkLength)
            .append(otherOptions)
            .toHashCode();
    }
}
