package com.netflix.aegisthus.output;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.netflix.Aegisthus;
import com.netflix.aegisthus.io.writable.RowWritable;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.CounterColumn;
import org.apache.cassandra.db.DeletedColumn;
import org.apache.cassandra.db.ExpiringColumn;
import org.apache.cassandra.db.OnDiskAtom;
import org.apache.cassandra.db.RangeTombstone;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.TypeParser;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.serializers.MarshalException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.text.NumberFormat;

public class JsonOutputFormat extends CustomFileNameFileOutputFormat<BytesWritable, RowWritable> {
    private static final Logger LOG = LoggerFactory.getLogger(JsonOutputFormat.class);
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance();

    static {
        NUMBER_FORMAT.setMinimumIntegerDigits(5);
        NUMBER_FORMAT.setGroupingUsed(false);
    }

    @SuppressWarnings("unchecked")
    private AbstractType<ByteBuffer> getConverter(Configuration conf, String key) {
        String converterType = conf.get(key);
        if (Strings.isNullOrEmpty(key)) {
            return BytesType.instance;
        }

        try {
            return (AbstractType<ByteBuffer>) TypeParser.parse(converterType);
        } catch (SyntaxException | ConfigurationException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public synchronized String getCustomFileName(TaskAttemptContext context, String name, String extension) {
        TaskID taskId = context.getTaskAttemptID().getTaskID();
        int partition = taskId.getId();
        return "aeg-" + NUMBER_FORMAT.format(partition) + extension;
    }

    @Override
    public RecordWriter<BytesWritable, RowWritable> getRecordWriter(TaskAttemptContext context) throws IOException {
        // No extension on the aeg json format files for historical reasons
        Path workFile = getDefaultWorkFile(context, "");
        FileSystem fs = workFile.getFileSystem(context.getConfiguration());
        final OutputStreamWriter writer = new OutputStreamWriter(fs.create(workFile, false), Charsets.UTF_8);
        final JsonFactory jsonFactory = new JsonFactory();
        final AbstractType<ByteBuffer> keyNameConverter = getConverter(
                context.getConfiguration(), Aegisthus.Feature.CONF_KEYTYPE
        );
        final AbstractType<ByteBuffer> columnNameConverter = getConverter(
                context.getConfiguration(), Aegisthus.Feature.CONF_COLUMNTYPE
        );
        final AbstractType<ByteBuffer> columnValueConverter = getConverter(
                context.getConfiguration(), Aegisthus.Feature.CONF_COLUMN_VALUE_TYPE
        );

        return new RecordWriter<BytesWritable, RowWritable>() {
            private int errorLogCount = 0;

            private String getString(AbstractType<ByteBuffer> converter, ByteBuffer buffer) {
                try {
                    return converter.getString(buffer);
                } catch (MarshalException e) {
                    if (errorLogCount < 100) {
                        LOG.error("Unable to use converter '{}'", converter, e);
                        errorLogCount++;
                    }
                    return BytesType.instance.getString(buffer);
                }
            }

            private String getString(AbstractType<ByteBuffer> converter, byte[] bytes) {
                return getString(converter, ByteBuffer.wrap(bytes));
            }

            @Override
            public void write(BytesWritable key, RowWritable rowWritable) throws IOException, InterruptedException {
                JsonGenerator jsonGenerator = jsonFactory.createGenerator(writer);
                jsonGenerator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

                String keyName = getString(keyNameConverter, key.getBytes());
                writer.write(keyName);

                writer.write('\t');

                jsonGenerator.writeStartObject();
                jsonGenerator.writeObjectFieldStart(keyName);
                jsonGenerator.writeNumberField("deletedAt", rowWritable.getDeletedAt());
                jsonGenerator.writeArrayFieldStart("columns");
                for (OnDiskAtom atom : rowWritable.getColumns()) {
                    if (atom instanceof Column) {
                        jsonGenerator.writeStartArray();
                        jsonGenerator.writeString(getString(columnNameConverter, atom.name()));
                        jsonGenerator.writeString(getString(columnValueConverter, ((Column) atom).value()));
                        jsonGenerator.writeNumber(((Column) atom).timestamp());

                        if (atom instanceof DeletedColumn) {
                            jsonGenerator.writeString("d");
                        } else if (atom instanceof ExpiringColumn) {
                            jsonGenerator.writeString("e");
                            jsonGenerator.writeNumber(((ExpiringColumn) atom).getTimeToLive());
                            jsonGenerator.writeNumber(atom.getLocalDeletionTime());
                        } else if (atom instanceof CounterColumn) {
                            jsonGenerator.writeString("c");
                            jsonGenerator.writeNumber(((CounterColumn) atom).timestampOfLastDelete());
                        }
                        jsonGenerator.writeEndArray();
                    } else if (atom instanceof RangeTombstone) {
                        LOG.debug("Range Tombstones are not output in the json format");
                    } else {
                        throw new IllegalStateException("Unknown atom type for atom: " + atom);
                    }
                }
                jsonGenerator.writeEndArray();
                jsonGenerator.writeEndObject(); // End key object
                jsonGenerator.writeEndObject(); // Outer json
                jsonGenerator.close();

                writer.write('\n');
            }

            @Override
            public void close(TaskAttemptContext context) throws IOException, InterruptedException {
                writer.close();
            }
        };
    }
}
