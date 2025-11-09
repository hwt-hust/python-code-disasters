package com.cloudinfra;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import java.io.IOException;

public class LineCounter extends Configured implements Tool {

    public static class LineCountMapper extends Mapper<LongWritable, Text, Text, IntWritable> {

        private final static IntWritable one = new IntWritable(1);
        private Text fileName = new Text();

        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {

            // Get the input file name
            FileSplit fileSplit = (FileSplit) context.getInputSplit();
            String fullPath = fileSplit.getPath().getName();

            // Set the filename as the key
            fileName.set(fullPath);

            // Emit (filename, 1) for each line
            context.write(fileName, one);
        }
    }

    public static class LineCountReducer extends Reducer<Text, IntWritable, Text, IntWritable> {

        private IntWritable result = new IntWritable();

        @Override
        protected void reduce(Text key, Iterable<IntWritable> values, Context context)
                throws IOException, InterruptedException {

            int sum = 0;

            // Sum up all the line counts
            for (IntWritable val : values) {
                sum += val.get();
            }

            result.set(sum);

            // Emit the result in the format: "filename": line_count
            context.write(new Text("\"" + key.toString() + "\""), result);
        }
    }

    @Override
    public int run(String[] args) throws Exception {

        if (args.length != 2) {
            System.err.println("Usage: LineCounter <input path> <output path>");
            return -1;
        }

        Configuration conf = getConf();

        // Create a new MapReduce job
        Job job = Job.getInstance(conf, "Line Counter");
        job.setJarByClass(LineCounter.class);

        // Set Mapper and Reducer classes
        job.setMapperClass(LineCountMapper.class);
        job.setCombinerClass(LineCountReducer.class);  // Combiner for optimization
        job.setReducerClass(LineCountReducer.class);

        // Set output key and value types
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(IntWritable.class);

        // Set input and output paths
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        // Set number of reduce tasks
        job.setNumReduceTasks(1);  // Single reducer to ensure all output in one file

        // Submit the job and wait for completion
        boolean success = job.waitForCompletion(true);

        return success ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new Configuration(), new LineCounter(), args);
        System.exit(exitCode);
    }
}
