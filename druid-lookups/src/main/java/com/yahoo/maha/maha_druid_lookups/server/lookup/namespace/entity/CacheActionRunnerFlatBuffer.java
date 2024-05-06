package com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.entity;

import com.google.common.base.Strings;
import com.google.flatbuffers.Table;
import com.yahoo.maha.maha_druid_lookups.query.lookup.DecodeConfig;
import com.yahoo.maha.maha_druid_lookups.query.lookup.namespace.ExtractionNameSpaceSchemaType;
import com.yahoo.maha.maha_druid_lookups.query.lookup.namespace.RocksDBExtractionNamespace;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.LookupService;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.MonitoringConstants;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.schema.flatbuffer.FlatBufferSchemaFactory;
import com.yahoo.maha.maha_druid_lookups.server.lookup.namespace.schema.flatbuffer.FlatBufferWrapper;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;
import org.rocksdb.RocksDB;

import java.util.Optional;

public class CacheActionRunnerFlatBuffer implements BaseCacheActionRunner {

    private static final Logger LOG = new Logger(CacheActionRunnerFlatBuffer.class);

    public byte[] getCacheValue(final String key
            , Optional<String> valueColumn
            , final Optional<DecodeConfig> decodeConfigOptional
            , RocksDB db
            , FlatBufferSchemaFactory flatBufferSchemaFactory
            , LookupService lookupService
            , ServiceEmitter emitter
            , RocksDBExtractionNamespace extractionNamespace) {
        try {
            if (db != null) {
                FlatBufferWrapper flatBuffer = flatBufferSchemaFactory.getFlatBuffer(extractionNamespace.getNamespace());
                byte[] cacheByteValue = db.get(key.getBytes());
                if(cacheByteValue == null) {
                    return new byte[0];
                }
                Table parsedMessage = flatBuffer.getFlatBuffer(cacheByteValue);
                String fBValue = flatBuffer.readFieldValue(valueColumn.get(), parsedMessage);
                LOG.debug("Extracted from flat buffer, field : %s, value %s", valueColumn.get(), fBValue);
                if (!decodeConfigOptional.isPresent()) {
                    return (fBValue == null) ? new byte[0] : fBValue.getBytes();
                } else { //handle decodeConfig
                    return handleDecode(decodeConfigOptional.get(), flatBuffer, parsedMessage).getBytes();
                }
            }
        } catch (Exception e) {
            LOG.error(e, "Caught exception while getting cache value");
            emitter.emit(ServiceMetricEvent.builder().setMetric(MonitoringConstants.MAHA_LOOKUP_GET_CACHE_VALUE_FAILURE, 1));
        }
        return null;
    }

    public String handleDecode(DecodeConfig decodeConfig, FlatBufferWrapper flatBufferWrapper, Table parsedMessage) throws Exception {
        try {
            String actualValue = flatBufferWrapper.readFieldValue(decodeConfig.getColumnToCheck(), parsedMessage);
            if (decodeConfig.getValueToCheck().equals(actualValue)) {
                return Strings.emptyToNull(flatBufferWrapper.readFieldValue(decodeConfig.getColumnIfValueMatched(), parsedMessage));
            } else {
                return Strings.emptyToNull(flatBufferWrapper.readFieldValue(decodeConfig.getColumnIfValueNotMatched(), parsedMessage));
            }
        } catch (Exception e ) {
            LOG.error(e, "Caught exception while handleDecode");
            throw e;
        }
    }

    synchronized public void updateCache(FlatBufferSchemaFactory flatBufferSchemaFactory
            , final String key
            , final byte[] value
            , RocksDB db
            , ServiceEmitter serviceEmitter
            , RocksDBExtractionNamespace extractionNamespace) {
        if (extractionNamespace.isCacheEnabled()) {
            try {
                FlatBufferWrapper flatBuffer = flatBufferSchemaFactory.getFlatBuffer(extractionNamespace.getNamespace());
                Table parsedMessage = flatBuffer.getFlatBuffer(value);
                Long newLastUpdated = Long.valueOf(flatBuffer.readFieldValue(extractionNamespace.getTsColumn(), parsedMessage));

                if (db != null) {
                    byte[] cacheValue = db.get(key.getBytes());
                    if(cacheValue != null) {
                        Table messageInDB = flatBuffer.getFlatBuffer(cacheValue);
                        Long lastUpdatedInDB = Long.valueOf(flatBuffer.readFieldValue(extractionNamespace.getTsColumn(), messageInDB));
                        if(newLastUpdated > lastUpdatedInDB) {
                            db.put(key.getBytes(), value);
                        }
                    } else {
                        db.put(key.getBytes(), value);
                    }
                    if (newLastUpdated > extractionNamespace.getLastUpdatedTime()) {
                        extractionNamespace.setLastUpdatedTime(newLastUpdated);
                    }
                    serviceEmitter.emit(ServiceMetricEvent.builder().setMetric(MonitoringConstants.MAHA_LOOKUP_UPDATE_CACHE_SUCCESS, Integer.valueOf(1)));
                }
            } catch (Exception e) {
                LOG.error(e, "Caught exception while updating cache");
                serviceEmitter.emit(ServiceMetricEvent.builder().setMetric(MonitoringConstants.MAHA_LOOKUP_UPDATE_CACHE_FAILURE, 1));
            }
        }
    }

    @Override
    public ExtractionNameSpaceSchemaType getSchemaType() {
        return ExtractionNameSpaceSchemaType.FLAT_BUFFER;
    }

    @Override
    public String toString() {
        return "CacheActionRunner{}";
    }

}
