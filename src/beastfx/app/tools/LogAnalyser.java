package beastfx.app.tools;


import static beast.base.parser.OutputUtils.format;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import beastfx.app.util.Utils;
import beast.base.core.BEASTVersion2;
import beast.base.core.Log;
import beast.base.inference.util.ESS;
import beast.base.parser.OutputUtils;
import beast.base.util.CollectionUtils;


public class LogAnalyser {
    public static final int BURN_IN_PERCENTAGE = 10; // default

    protected final String fileName;

    /**
     * column labels in log file *
     */
    protected String[] m_sLabels;

    /**
     * distinguish various column types *
     */
    protected enum type {
        REAL, INTEGER, BOOL, NOMINAL
    }

    protected type[] m_types;
    /**
     * range of a column, if it is not a REAL *
     */
    protected List<String>[] m_ranges;

    public List<String>[] getRanges() {return m_ranges;}

    public void setRanges(List<String>[] ranges) {this.m_ranges = ranges;}


    /**
     * data from log file with burn-in removed *
     */
    protected Double[][] m_fTraces;

    /**
     * statistics on the data, one per column. First column (sample nr) is not set *
     */
    Double[] m_fMean, m_fStdError, m_fStdDev, m_fMedian, m_f95HPDup, m_f95HPDlow, m_fESS, m_fACT, m_fGeometricMean;

    public Double[] getMean() {
        return m_fMean;
    }

    public void setMean(Double[] m_fMean) {
        this.m_fMean = m_fMean;
    }

    public Double[] getStdError() {
        return m_fStdError;
    }

    public void setStdError(Double[] m_fStdError) {
        this.m_fStdError = m_fStdError;
    }

    public Double[] getStdDev() {
        return m_fStdDev;
    }

    public void setStdDev(Double[] m_fStdDev) {
        this.m_fStdDev = m_fStdDev;
    }

    public Double[] getMedian() {
        return m_fMedian;
    }

    public void setMedian(Double[] m_fMedian) {
        this.m_fMedian = m_fMedian;
    }

    public Double[] getESS() {
        return m_fESS;
    }

    public void setESS(Double[] m_fESS) {
        this.m_fESS = m_fESS;
    }

    public Double[] getACT() {
        return m_fACT;
    }

    public void setACT(Double[] m_fACT) {
        this.m_fACT = m_fACT;
    }

    public Double[] getGeometricMean() {
        return m_fGeometricMean;
    }

    public void setGeometricMean(Double[] m_fGeometricMean) {
        this.m_fGeometricMean = m_fGeometricMean;
    }

	public Double[] get95HPDup() {
		return m_f95HPDup;
	}

	public void set95HPDup(Double[] m_f95HPDup) {
		this.m_f95HPDup = m_f95HPDup;
	}

	public Double[] get95HPDlow() {
		return m_f95HPDlow;
	}

	public void set95HPDlow(Double[] m_f95HPDlow) {
		this.m_f95HPDlow = m_f95HPDlow;
	}

	public Double[][] getTraces() {
		return m_fTraces;
	}

	/**
     * used for storing comments before the actual log file commences *
     */
    protected String m_sPreAmble;

    /**
     * If set, analyzer works in "quiet" mode.
     */
    protected boolean quiet = false;

    final protected static String BAR = "|---------|---------|---------|---------|---------|---------|---------|---------|";

    public LogAnalyser() {
        fileName = null;
    }

    /**
     *
     * @param args
     * @param burnInPercentage  burnInPercentage typical = 10; percentage of data that can be ignored
     * @throws IOException
     */
    public LogAnalyser(String[] args, int burnInPercentage) throws IOException {
    	this(args, burnInPercentage, false, true);
    }

    public LogAnalyser(String[] args, int burnInPercentage, boolean quiet, boolean calcStats) throws IOException {
        fileName = args[args.length - 1];
        readLogFile(fileName, burnInPercentage);
        this.quiet = quiet;
        if (calcStats) {
        	calcStats(null);
        }
    }

    public LogAnalyser(String[] args) throws IOException {
        this(args, BURN_IN_PERCENTAGE, false, true);
    }

    public LogAnalyser(String fileName, int burnInPercentage) throws IOException {
    	this(fileName, burnInPercentage, false, true, null);
    }

    public LogAnalyser(String fileName, int burnInPercentage, String [] tags) throws IOException {
    	this(fileName, burnInPercentage, false, true, tags);
    }

    public LogAnalyser(String fileName, int burnInPercentage, boolean quiet) throws IOException {
    	this(fileName, burnInPercentage, quiet, true, null);
    }

    public LogAnalyser(String fileName, int burnInPercentage, boolean quiet, String [] tags) throws IOException {
    	this(fileName, burnInPercentage, quiet, true, tags);
    }

    public LogAnalyser(String fileName) throws IOException {
        this(fileName, BURN_IN_PERCENTAGE);
    }

    public LogAnalyser(String fileName, int burnInPercentage, boolean quiet, boolean calcStats) throws IOException {
    	this(fileName, burnInPercentage, quiet, calcStats, null);
    }

    public LogAnalyser(String fileName, int burnInPercentage, boolean quiet, boolean calcStats, String [] tags) throws IOException {
        this.fileName = fileName;
        this.quiet = quiet;
        readLogFile(fileName, burnInPercentage);
        if (calcStats) {
        	calcStats(tags);
        }
    }

    @SuppressWarnings("unchecked")
	protected void readLogFile(String fileName, int burnInPercentage) throws IOException {
        log("\nLoading " + fileName);
        BufferedReader fin = new BufferedReader(new FileReader(fileName));
        String str;
        m_sPreAmble = "";
        m_sLabels = null;
        int data = 0;
        // first, sweep through the log file to determine size of the log
        while (fin.ready()) {
            str = fin.readLine();
            if (str.indexOf('#') < 0 && str.matches(".*[0-9a-zA-Z].*")) {
                if (m_sLabels == null)
                    m_sLabels = str.split("\\t");
                else
                    data++;
            } else {
                m_sPreAmble += str + "\n";
            }
        }
        int lines = Math.max(1, data / 80);
        // reserve memory
        int items = m_sLabels.length;
        m_ranges = new List[items];
        int burnIn = data * burnInPercentage / 100;
        int total = data - burnIn;
        m_fTraces = new Double[items][data - burnIn];
        fin.close();
        fin = new BufferedReader(new FileReader(fileName));
        data = -burnIn - 1;
        logln(", burnin " + burnInPercentage + "%, skipping " + burnIn + " log lines\n\n" + BAR);
        // grab data from the log, ignoring burn in samples
        m_types = new type[items];
        Arrays.fill(m_types, type.INTEGER);
        int reported = 0;
        while (fin.ready()) {
            str = fin.readLine();
            int i = 0;
            if (str.indexOf('#') < 0 && str.matches("[-0-9].*"))
                if (++data >= 0  && data < m_fTraces[0].length)
                    for (String str2 : str.split("\\s")) {
                        try {
                            if (str2.indexOf('.') >= 0) {
                                m_types[i] = type.REAL;
                            }
                            m_fTraces[i][data] = Double.parseDouble(str2);
                        } catch (Exception e) {
                            if (m_ranges[i] == null) {
                                m_ranges[i] = new ArrayList<>();
                            }
                            if (!m_ranges[i].contains(str2)) {
                                m_ranges[i].add(str2);
                            }
                            m_fTraces[i][data] = 1.0 * m_ranges[i].indexOf(str2);
                        }
                        i++;
                    }
			while (reported < 81 && 1000.0 * reported < 81000.0 * (data + 1)/ total) {
                log("*");
                reported++;
    	    }
        }
        logln("");
        // determine types
        for (int i = 0; i < items; i++)
            if (m_ranges[i] != null)
                if (m_ranges[i].size() == 2 && m_ranges[i].contains("true") && m_ranges[i].contains("false") ||
                        m_ranges[i].size() == 1 && (m_ranges[i].contains("true") || m_ranges[i].contains("false")))
                    m_types[i] = type.BOOL;
                else
                    m_types[i] = type.NOMINAL;

        fin.close();
    } // readLogFile

    /**
     * calculate statistics on the data, one per column.
     * First column (sample nr) is not set *
     */
    public void calcStats() {
    	calcStats(null);
    }

    public void calcStats(String [] tags) {
        logln("\nCalculating statistics\n\n" + BAR);
        int stars = 0;
        int items = m_sLabels.length;
        m_fMean = new Double[items];
        m_fStdError = new Double[items];
        m_fStdDev = new Double[items];
        m_fMedian = new Double[items];
        m_f95HPDlow = new Double[items];
        m_f95HPDup = new Double[items];
        m_fESS = new Double[items];
        m_fACT = new Double[items];
        m_fGeometricMean = new Double[items];
        int sampleInterval = (int) (m_fTraces[0][1] - m_fTraces[0][0]);
        for (int i = 1; i < items; i++) {
        	if (matchesTags(tags, i)) {
	            // calc mean and standard deviation
	            Double[] trace = m_fTraces[i];
	            double sum = 0, sum2 = 0;
	            for (double f : trace) {
	                sum += f;
	                sum2 += f * f;
	            }
	            if (m_types[i] != type.NOMINAL) {
	                m_fMean[i] = sum / trace.length;
	                m_fStdDev[i] = Math.sqrt(sum2 / trace.length - m_fMean[i] * m_fMean[i]);
	            } else {
	                m_fMean[i] = Double.NaN;
	                m_fStdDev[i] = Double.NaN;
	            }

	            if (m_types[i] == type.REAL || m_types[i] == type.INTEGER) {
	                // calc median, and 95% HPD interval
	                Double[] sorted = trace.clone();
	                Arrays.sort(sorted);
	                m_fMedian[i] = sorted[trace.length / 2];
	                // n instances cover 95% of the trace, reduced down by 1 to match Tracer
	                int n = (int) ((sorted.length - 1) * 95.0 / 100.0);
	                double minRange = Double.MAX_VALUE;
	                int hpdIndex = 0;
	                for (int k = 0; k < sorted.length - n; k++) {
	                    double range = sorted[k + n] - sorted[k];
	                    if (range < minRange) {
	                        minRange = range;
	                        hpdIndex = k;
	                    }
	                }
	                m_f95HPDlow[i] = sorted[hpdIndex];
	                m_f95HPDup[i] = sorted[hpdIndex + n];

	                // calc effective sample size
	                m_fACT[i] = ESS.ACT(m_fTraces[i], sampleInterval);
	                m_fStdError[i] = ESS.stdErrorOfMean(trace, sampleInterval);
	                m_fESS[i] = trace.length / (m_fACT[i] / sampleInterval);

	                // calc geometric mean
	                if (sorted[0] > 0) {
	                    // geometric mean is only defined when all elements are positive
	                    double gm = 0;
	                    for (double f : trace)
	                        gm += Math.log(f);
	                    m_fGeometricMean[i] = Math.exp(gm / trace.length);
	                } else
	                    m_fGeometricMean[i] = Double.NaN;
	            }
            } else {
                m_fMedian[i] = Double.NaN;
                m_f95HPDlow[i] = Double.NaN;
                m_f95HPDup[i] = Double.NaN;
                m_fACT[i] = Double.NaN;
                m_fESS[i] = Double.NaN;
                m_fGeometricMean[i] = Double.NaN;
            }
            while (stars < 80 * (i + 1) / items) {
                log("*");
                stars++;
            }
        }
        logln("\n");
    } // calcStats

    public void setData(Double[][] traces, String[] labels, type[] types) {
        m_fTraces = traces.clone();
        m_sLabels = labels.clone();
        m_types = types.clone();
        calcStats();
    }

    public void setData(Double[] trace, int sampleStep) {
        Double[][] traces = new Double[2][];
        traces[0] = new Double[trace.length];
        for (int i = 0; i < trace.length; i++) {
            traces[0][i] = (double) i * sampleStep;
        }
        traces[1] = trace.clone();
        setData(traces, new String[]{"column", "data"}, new type[]{type.REAL, type.REAL});
    }

    public int indexof(String label) {
        return CollectionUtils.indexof(label, m_sLabels);
	}

    /**
     * First column "Sample" (sample nr) needs to be removed
     * @return
     */
    public List<String> getLabels() {
        if (m_sLabels.length < 2)
            return new ArrayList<>();
        return CollectionUtils.toList(m_sLabels, 1, m_sLabels.length);
    }

    public Double [] getTrace(int index) {
    	return m_fTraces[index].clone();
    }

    public Double [] getTrace(String label) {
    	return m_fTraces[indexof(label)].clone();
    }

    public double getMean(String label) {
        return getMean(indexof(label));
    }

    public double getStdError(String label) {
        return getStdError(indexof(label));
    }

    public double getStdDev(String label) {
        return getStdDev(indexof(label));
    }

    public double getMedian(String label) {
        return getMedian(indexof(label));
    }

    public double get95HPDup(String label) {
        return get95HPDup(indexof(label));
    }

    public double get95HPDlow(String label) {
        return get95HPDlow(indexof(label));
    }

    public double getESS(String label) {
        return getESS(indexof(label));
    }

    public double getACT(String label) {
        return getACT(indexof(label));
    }

    public double getGeometricMean(String label) {
        return getGeometricMean(indexof(label));
    }

    public double getMean(int column) {
        return m_fMean[column];
    }

    public double getStdDev(int column) {
        return m_fStdDev[column];
    }

    public double getStdError(int column) {
        return m_fStdError[column];
    }

    public double getMedian(int column) {
        return m_fMedian[column];
    }

    public double get95HPDup(int column) {
        return m_f95HPDup[column];
    }

    public double get95HPDlow(int column) {
        return m_f95HPDlow[column];
    }

    public double getESS(int column) {
        return m_fESS[column];
    }

    public double getACT(int column) {
        return m_fACT[column];
    }

    public double getGeometricMean(int column) {
        return m_fGeometricMean[column];
    }

    public double getMean(Double[] trace) {
        setData(trace, 1);
        return m_fMean[1];
    }

    public double getStdDev(Double[] trace) {
        setData(trace, 1);
        return m_fStdDev[1];
    }

    public double getMedian(Double[] trace) {
        setData(trace, 1);
        return m_fMedian[1];
    }

    public double get95HPDup(Double[] trace) {
        setData(trace, 1);
        return m_f95HPDup[1];
    }

    public double get95HPDlow(Double[] trace) {
        setData(trace, 1);
        return m_f95HPDlow[1];
    }

    public double getESS(Double[] trace) {
        setData(trace, 1);
        return m_fESS[1];
    }

    public double getACT(Double[] trace, int sampleStep) {
        setData(trace, sampleStep);
        return m_fACT[1];
    }

    public double getGeometricMean(Double[] trace) {
        setData(trace, 1);
        return m_fGeometricMean[1];
    }

    public String getLogFile() {
        return fileName;
    }

    /**
     * print statistics for each column except first column (sample nr). *
     */
    final String SPACE = OutputUtils.SPACE;
    public void print(PrintStream out) {
    	print(out, null);
    }

    public void print(PrintStream out, String [] tags) {
    	// set up header for prefix, if any is specified
    	String prefix = System.getProperty("prefix");
    	String prefixHead = (prefix == null ? "" : "prefix ");
    	if (prefix != null) {
	    	String [] p = prefix.trim().split("\\s+");
	    	if (p.length > 1) {
	    		prefixHead = "";
	    		for (int i = 0; i < p.length; i++) {
	    			prefixHead += "prefix" + i + " ";
	    		}
	    	}
    	}

        try {
            // delay so that stars can be flushed from stderr
            Thread.sleep(100);
        } catch (Exception e) {
        }
        int max = 0;
        for (int i = 1; i < m_sLabels.length; i++)
            max = Math.max(m_sLabels[i].length(), max);
        String space = "";
        for (int i = 0; i < max; i++)
            space += " ";

        out.println("item" + space.substring(4) + " " + prefixHead +
        		format("mean") + format("stderr")  + format("stddev")  + format("median")  + format("95%HPDlo")  + format("95%HPDup")  + format("ACT")  + format("ESS")  + format("geometric-mean"));
        for (int i = 1; i < m_sLabels.length; i++) {
        	if (m_fStdError[i] != null) {
        		out.println(m_sLabels[i] + space.substring(m_sLabels[i].length()) + SPACE + (prefix == null ? "" : prefix + SPACE) +
                    format(m_fMean[i]) + SPACE + format(m_fStdError[i]) + SPACE + format(m_fStdDev[i]) +
                    SPACE + format(m_fMedian[i]) + SPACE + format(m_f95HPDlow[i]) + SPACE + format(m_f95HPDup[i]) +
                    SPACE + format(m_fACT[i]) + SPACE + format(m_fESS[i]) + SPACE + format(m_fGeometricMean[i]));
        	}
        }
    }

    /**
     * Display header used in one-line mode.
     *
     * @param out output stream
     */
    public void printOneLineHeader(PrintStream out) {
    	printOneLineHeader(out, null);
    }

    public void printOneLineHeader(PrintStream out, String [] tags) {
        String[] postFix = {
                "mean", "stderr", "stddev",
                "median", "95%HPDlo", "95%HPDup",
                "ACT", "ESS", "geometric-mean"
        };

        out.print("sample\tfilename");
        for (int paramIdx=1; paramIdx<m_sLabels.length; paramIdx++) {
        	if (matchesTags(tags, paramIdx)) {
	            for (int i=0; i<postFix.length; i++) {
	                out.print("\t");
	                out.print(m_sLabels[paramIdx] + "." + postFix[i]);
	            }
        	}
        }

        out.println("\t");
    }


    private boolean matchesTags(String [] tags, int paramIdx) {
    	if (tags == null) {
    		return true;
    	}
		String label = m_sLabels[paramIdx];
		for (String tag:tags) {
			if (label.equals(tag)) {
				return true;
			}
		}
		return false;
    }

    /**
     * Display results for single log on one line.
     *
     * @param out output stream
     */
    public void printOneLine(PrintStream out) {
        printOneLine(out, null);
    }

    public void printOneLine(PrintStream out, String [] tags) {
        for (int paramIdx=1; paramIdx<m_sLabels.length; paramIdx++) {
	        if (matchesTags(tags, paramIdx)) {
	            out.print(m_fMean[paramIdx] + "\t");
	            out.print(m_fStdError[paramIdx] + "\t");
	            out.print(m_fStdDev[paramIdx] + "\t");
	            out.print(m_fMedian[paramIdx] + "\t");
	            out.print(m_f95HPDlow[paramIdx] + "\t");
	            out.print(m_f95HPDup[paramIdx] + "\t");
	            out.print(m_fACT[paramIdx] + "\t");
	            out.print(m_fESS[paramIdx] + "\t");
	            out.print(m_fGeometricMean[paramIdx] + "\t");
            }
        }

        out.println();
    }

    protected void log(String s) {
        if (!quiet)
            Log.warning.print(s);
    }

    protected void logln(String s) {
        if (!quiet)
        	Log.warning.println(s);
    }

    static void printUsageAndExit() {
    	System.out.println("LogAnalyser [-b <burninPercentage] [file1] ... [filen]");
    	System.out.println("-burnin <burninPercentage>");
    	System.out.println("--burnin <burninPercentage>");
    	System.out.println("-b <burninPercentage> percentage of log file to disregard, default " + BURN_IN_PERCENTAGE);
    	System.out.println("-t <tag>[,tag]+ comma separates list of tags to be processed. If nothing is specified all tags are processed and displayed.");
        System.out.println("-oneline Display only one line of output per file.\n" +
                "         Header is generated from the first file only.\n" +
                "         (Implies quiet mode.)");
        System.out.println("-threads <threadcount> number of threads to use in oneline mode.");
        System.out.println("-quiet Quiet mode.  Avoid printing status updates to stderr.");
    	System.out.println("-help");
    	System.out.println("--help");
    	System.out.println("-h print this message");
    	System.out.println("[fileX] log file to analyse. Multiple files are allowed, each is analysed separately");
    	System.exit(0);
    }

    class CoreRunnable implements java.lang.Runnable {
        static private boolean headerPrinted = false;
        static private int lineNr = 0;
        int start, end;
        CountDownLatch countDown;
        List<String> files;
        int burnInPercentage;
        String [] tags;

        CoreRunnable(int start, int end, List<String> files, CountDownLatch countDown, int burnInPercentage, String [] tags) {
            this.start = start;
            this.end = end;
            this.countDown = countDown;
            this.files = files;
            this.burnInPercentage = burnInPercentage;
            this.tags = tags;
        }

        @Override
		public void run() {
        	for (int i = start; i < end; i++) {
        		LogAnalyser analyser;
				try {
					analyser = new LogAnalyser(files.get(i), burnInPercentage, true, tags);
                    if (i == 0) {
                        analyser.printOneLineHeader(System.out, tags);
                        headerPrinted = true;
                    }
                    while (!headerPrinted) {
						Thread.sleep(500);
                    }

                    synchronized (countDown) {
                    	System.out.print(lineNr + "\t" + files.get(i) + "\t");
                    	lineNr++;
                    	analyser.printOneLine(System.out, tags);
	                }
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
        	}
            countDown.countDown();
        }

    } // CoreRunnable


    /**
     * @param args
     */
    public static void main(String[] args) {
        try {
            LogAnalyser analyser;
            	// process args
            	int burninPercentage = BURN_IN_PERCENTAGE;
                boolean oneLine = false;
                boolean quiet = false;
            	List<String> files = new ArrayList<>();
            	String [] tags = null;
            	int threads = 1;
            	int i = 0;
            	while (i < args.length) {
            		String arg = args[i];
                    switch (arg) {
            		case "-b":
            		case "-burnin":
            		case "--burnin":
            			if (i+1 >= args.length) {
            				Log.warning.println("-b argument requires another argument");
            				printUsageAndExit();
            			}
            			burninPercentage = Integer.parseInt(args[i+1]);
            			i += 2;
            			break;
            		case "-t":
            		case "-tag":
            		case "--tag":
            			if (i+1 >= args.length) {
            				Log.warning.println("-t argument requires another argument");
            				printUsageAndExit();
            			}
            			tags = args[i+1].trim().split(",");
            			i += 2;
            			break;
                    case "-oneline":
                        oneLine = true;
                        i += 1;
                        break;

                    case "-quiet":
                        quiet = true;
                        i += 1;
                        break;

                    case "-threads":
            			if (i+1 >= args.length) {
            				Log.warning.println("-threads argument requires another argument");
            				printUsageAndExit();
            			}
            			threads = Integer.parseInt(args[i+1].trim());
            			i += 2;
                        break;
            		case "-h":
            		case "-help":
            		case "--help":
            			printUsageAndExit();
            			break;
            		default:
            			if (arg.startsWith("-")) {
            				Log.warning.println("unrecognised command " + arg);
            				printUsageAndExit();
            			}
            			files.add(arg);
            			i++;
            		}
            	}
            	if (files.size() == 0) {
            		// no file specified, open file dialog to select one
	                BEASTVersion2 version = new BEASTVersion2();
	                File file = Utils.getLoadFile("LogAnalyser " + version.getVersionString() + " - Select log file to analyse",
	                        null, "BEAST log (*.log) Files", "log", "txt");
	                if (file == null) {
	                    return;
	                }
	                analyser = new LogAnalyser(file.getAbsolutePath(), burninPercentage, quiet, tags);
	                analyser.print(System.out, tags);
            	} else {
            		// process files
                    if (oneLine) {
                    	if (threads > 0) {
                    		ExecutorService exec = Executors.newFixedThreadPool(threads);
                    		CountDownLatch countDown = new CountDownLatch(threads);
                    		int start = 0;
                    		for (int j = 0; j < threads; j++) {
                    			int end = (j+1) * files.size()/threads;
                                CoreRunnable coreRunnable = new LogAnalyser().new CoreRunnable(start, end, files, countDown, burninPercentage, tags);
                                exec.execute(coreRunnable);
                                start = end;

                    		}
                    		countDown.await();

                            // gracefully exit
                            exec.shutdownNow();
                            System.exit(0);

                    	} else {
	                        for (int idx=0; idx<files.size(); idx++) {
	                            analyser = new LogAnalyser(files.get(idx), burninPercentage, true, tags);

	                            if (idx == 0) {
	                                analyser.printOneLineHeader(System.out, tags);
	                            }

	                            System.out.print(idx + "\t" + files.get(idx) + "\t");
	                            analyser.printOneLine(System.out, tags);
	                        }
                    	}

                    } else {
                        for (String file : files) {
                            analyser = new LogAnalyser(file, burninPercentage, quiet, tags);
                            analyser.print(System.out, tags);
                        }
                    }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
