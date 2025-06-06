/*
 * TreeAnnotator.java
 *
 * Copyright (C) 2002-2010 Alexei Drummond and Andrew Rambaut
 *
 * This file is part of BEAST.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership and licensing.
 *
 * BEAST is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * BEAST is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with BEAST; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin St, Fifth Floor,
 * Boston, MA  02110-1301  USA
 */

package beastfx.app.treeannotator;


import java.io.*;
import java.util.*;

import beastfx.app.tools.Application;
import beastfx.app.treeannotator.services.NodeHeightSettingService;
import beastfx.app.treeannotator.services.TopologySettingService;
import beastfx.app.treeannotator.services.UserTargetTreeTopologyService;
import beast.base.core.Description;
import beast.base.core.Input;
import beast.base.core.Log;
import beast.base.evolution.alignment.TaxonSet;
import beast.base.evolution.tree.Node;
import beast.base.evolution.tree.Tree;
import beast.base.evolution.tree.TreeParser;
import beast.base.evolution.tree.TreeUtils;
import beast.base.parser.NexusParser;
import beast.base.util.CollectionUtils;
import beast.base.util.DiscreteStatistics;
import beast.base.util.HeapSort;
import beast.pkgmgmt.BEASTClassLoader;
import beast.pkgmgmt.BEASTVersion;
import beast.pkgmgmt.PackageManager;

/**
 * @author Alexei Drummond
 * @author Andrew Rambaut
 * 
 * TreeAnnotator ported from BEAST 1
 */
@Description("TreeAnnotator is a tool for summarising tree sets in by a summary tree and annotating the branches with summary statistics as metadata")
public class TreeAnnotator extends beast.base.inference.Runnable {
    final public Input<String> heightInput = new Input<> ("height", "name of the method for estimating node heights", "CA");
    final public Input<Integer> burnInPercentageInput = new Input<>("burnin", "percentage of trees to used as burn-in (and will be ignored)", 10);
    final public Input<Double> limitInput = new Input<>("limit", "the minimum posterior probability for a node to be annotated", 0.0);
    final public Input<String> topologyInput = new Input<> ("topology", "name of the method for determining topology", "MCC");
    final public Input<String> targetInput = new Input<> ("target", "target_file_name, specifies a user target tree to be annotated");
    final public Input<Boolean> forceDiscreteInput = new Input<> ("forceDiscrete", "forces integer traits to be treated as discrete traits.");
    final public Input<Boolean> lowMemInput = new Input<> ("lowMem", "use less memory, which is a bit slower.");
    final public Input<Double> hpd2DInput = new Input<> ("hpd2D", "the HPD interval to be used for the bivariate traits");
    final public Input<Boolean> nohpd2DInput = new Input<> ("nohpd2D", "suppress calculation of HPD intervals for the bivariate traits");
    final public Input<Boolean> noSAInput = new Input<> ("noSA", "interpret the tree set as begin from a not being from a sampled ancestor analysis, even if there are zero branch lengths in the tree set");
    final public Input<List<String>> versionFileInput = new Input<> ("version_file", "Provide a version file containing a list of services to explicitly allow. (Useful for package development.)", new ArrayList<>());

    public final static BEASTVersion version = new BEASTVersion();

    private final static boolean USE_R = false;

    private static boolean forceIntegerToDiscrete = false;

    static boolean processSA = true;

    private boolean SAmode = false;
    
    public NodeHeightSettingService nodeHeightSettingService;
    public TopologySettingService topologySettingService;
    private int burninPercentage;
    // arguments that do not set any input option
	public Input<List<File>> filesInput = new  Input<> ("file", "Specify the input filename and (optional) output file name" , new ArrayList<>());

    public abstract class TreeSet {
    	public abstract boolean hasNext();
    	public abstract Tree next() throws IOException;
    	public abstract void reset() throws IOException;


    	public String inputFileName;
        public int burninCount = 0;
        public int totalTrees = 0;
        public boolean isNexus = true;

        /** determine number of trees in the file,
    	 * and number of trees to skip as burnin
    	 * @throws IOException
    	 * @throws FileNotFoundException **/
    	void countTrees(int burninPercentage) throws IOException  {
            BufferedReader fin = new BufferedReader(new FileReader(new File(inputFileName)));
            if (!fin.ready()) {
            	throw new IOException("File appears empty");
            }
        	String str = fin.readLine();
            if (!str.toUpperCase().trim().startsWith("#NEXUS")) {
            	// the file contains a list of Newick trees instead of a list in Nexus format
            	isNexus = false;
            	if (str.trim().length() > 0) {
            		totalTrees = 1;
            	}
            }
            while (fin.ready()) {
            	str = fin.readLine();
                if (isNexus) {
                    if (str.trim().toLowerCase().startsWith("tree ")) {
                    	totalTrees++;
                    }
                } else if (str.trim().length() > 0) {
            		totalTrees++;
                }
            }
            fin.close();

            burninCount = Math.max(0, (burninPercentage * totalTrees)/100);

            progressStream.println("Processing " + (totalTrees - burninCount) + " trees from file" +
                    (burninPercentage > 0 ? " after ignoring first " + burninPercentage + "% = " + burninCount + " trees." : "."));
		}

    }    
    
    public class FastTreeSet extends TreeSet {
    	int current = 0;
    	Tree [] trees;

    	public FastTreeSet(String inputFileName, int burninPercentage) throws IOException  {
            this.inputFileName = inputFileName;
            countTrees(burninPercentage);

            List<Tree> parsedTrees;
            if (isNexus) {
                NexusParser nexusParser = new NexusParser();
                nexusParser.parseFile(new File(inputFileName));
                parsedTrees = nexusParser.trees;
            } else {
                BufferedReader fin = new BufferedReader(new FileReader(inputFileName));
                parsedTrees = new ArrayList<>();
                current = 0;
                while (fin.ready()) {
                    String line = fin.readLine().trim();

                    String id = "" + current++;
                    try {
                    	int i = line.indexOf("(");
    	                id = line.substring(5, i).split("=")[0].trim();
                    } catch (Exception e) {
                    	// ignore
                    }

                    Tree thisTree;
                    try {
                        thisTree = new TreeParser(null, line, 0, false);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        thisTree = new TreeParser(null, line, 1, false);
                    }
                    thisTree.setID(id);
                    
                    parsedTrees.add(thisTree);
                }
                fin.close();
                current = 0;
            }

            int treesToUse = parsedTrees.size() - burninCount;
	      	trees = new Tree[treesToUse];
            for (int i=burninCount; i<parsedTrees.size(); i++)
                trees[i-burninCount] = parsedTrees.get(i);
		}

		@Override
		public boolean hasNext() {
			return current < trees.length;
		}

		@Override
		public Tree next()  {
			return trees[current++];
		}

		@Override
		public void reset()  {
			current = 0;
		}
    }
    
    public class MemoryFriendlyTreeSet extends TreeSet {
//    	Tree [] trees;
    	int current = 0;
    	int lineNr;
        public Map<String, String> translationMap = null;
        public List<String> taxa;

        // label count origin for NEXUS trees
        int origin = -1;

        BufferedReader fin;

        public MemoryFriendlyTreeSet(String inputFileName, int burninPercentage) throws IOException  {
    		this.inputFileName = inputFileName;
    		countTrees(burninPercentage);

            fin = new BufferedReader(new FileReader(inputFileName));
    	}


    	@Override
    	public void reset() throws FileNotFoundException  {
    		current = 0;
            fin = new BufferedReader(new FileReader(new File(inputFileName)));
            lineNr = 0;
            try {
                if (isNexus) {
	                while (fin.ready()) {
	                    final String str = nextLine();
	                    if (str == null) {
	                        return;
	                    }
	                    final String lower = str.toLowerCase();
	                    if (lower.matches("^\\s*begin\\s+trees;\\s*$")) {
	                        parseTreesBlock();
	                        return;
	                    }
	                }
                } else {
                    while (fin.ready() && lineNr < burninCount) {
                        final String str = nextLine();
                        if (str == null) {
                            return;
                        }
                        if (str.trim().length() > 2 && !str.trim().startsWith("#")) {
                        	lineNr++;
                        }
                    }                	
                }
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException("Around line " + lineNr + "\n" + e.getMessage());
            }
        } // parseFile

        /**
         * read next line from Nexus file that is not a comment and not empty 
         * @throws IOException *
         */
        String nextLine() throws IOException  {
            String str = readLine();
            if (str == null) {
                return null;
            }
            if (str.matches("^\\s*\\[.*")) {
                final int start = str.indexOf('[');
                int end = str.indexOf(']', start);
                while (end < 0) {
                    str += readLine();
                    end = str.indexOf(']', start);
                }
                str = str.substring(0, start) + str.substring(end + 1);
                if (str.matches("^\\s*$")) {
                    return nextLine();
                }
            }
            if (str.matches("^\\s*$")) {
                return nextLine();
            }
            return str;
        }

        /**
         * read line from nexus file *
         */
        String readLine() throws IOException {
            if (!fin.ready()) {
                return null;
            }
            lineNr++;
            return fin.readLine();
        }

        private void parseTreesBlock() throws IOException  {
            // read to first non-empty line within trees block
        	fin.mark(1024*1024);
        	int lineNr = this.lineNr;
            String str = readLine().trim();
            while (str.equals("")) {
            	fin.mark(1024*1024);
            	lineNr = this.lineNr;
                str = readLine().trim();
            }

            // if first non-empty line is "translate" then parse translate block
            if (str.toLowerCase().contains("translate")) {
                translationMap = parseTranslateBlock();
                origin = getIndexedTranslationMapOrigin(translationMap);
                if (origin != -1) {
                    taxa = getIndexedTranslationMap(translationMap, origin);
                }
            } else {
            	this.lineNr = lineNr;
            	fin.reset();
            }
            // we got to the end of the translate block
            // read burninCount trees
            current = 0;
            while (current < burninCount && fin.ready()) {
    			str = nextLine();
                if (str.trim().toLowerCase().startsWith("tree ")) {
                	current++;
                }
            }
        }

        private List<String> getIndexedTranslationMap(final Map<String, String> translationMap, final int origin) {

            //System.out.println("translation map size = " + translationMap.size());

            final String[] taxa = new String[translationMap.size()];

            for (final String key : translationMap.keySet()) {
                taxa[Integer.parseInt(key) - origin] = translationMap.get(key);
            }
            return Arrays.asList(taxa);
        }

        /**
         * @param translationMap
         * @return minimum key value if keys are a contiguous set of integers starting from zero or one, -1 otherwise
         */
        private int getIndexedTranslationMapOrigin(final Map<String, String> translationMap) {

            final SortedSet<Integer> indices = new java.util.TreeSet<>();

            int count = 0;
            for (final String key : translationMap.keySet()) {
                final int index = Integer.parseInt(key);
                indices.add(index);
                count += 1;
            }
            if ((indices.last() - indices.first() == count - 1) && (indices.first() == 0 || indices.first() == 1)) {
                return indices.first();
            }
            return -1;
        }

        /**
         * @return a map of taxa translations, keys are generally integer node number starting from 1
         *         whereas values are generally descriptive strings.
         * @throws IOException
         */
        private Map<String, String> parseTranslateBlock() throws IOException {

            final Map<String, String> translationMap = new HashMap<>();

            String line = readLine();
            final StringBuilder translateBlock = new StringBuilder();
            while (line != null && !line.trim().toLowerCase().equals(";")) {
                translateBlock.append(line.trim());
                line = readLine();
            }
            final String[] taxaTranslations = translateBlock.toString().split(",");
            for (final String taxaTranslation : taxaTranslations) {
                final String[] translation = taxaTranslation.split("[\t ]+");
                if (translation.length == 2) {
                    translationMap.put(translation[0], translation[1]);
//                    System.out.println(translation[0] + " -> " + translation[1]);
                } else {
                    Log.err.println("Ignoring translation:" + Arrays.toString(translation));
                }
            }
            return translationMap;
        }

    	
    	
    	@Override
    	public boolean hasNext() {
    		return current < totalTrees;
    	}
    	
    	@Override
    	public Tree next() throws IOException {
			String str = nextLine();
    		if (!isNexus) {
                TreeParser treeParser;
                if (taxa == null) {
                	collectTaxaNames(str);
                }
            	current++;

                if (origin != -1) {
                    treeParser = new TreeParser(taxa, str, origin, false);
                } else {
                    try {
                        treeParser = new TreeParser(taxa, str, 0, false);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        treeParser = new TreeParser(taxa, str, 1, false);
                    }
                }
                return treeParser;
    		}
    		
            // read trees from NEXUS file
            if (str.trim().toLowerCase().startsWith("tree ")) {
            	current++;
                final int i = str.indexOf('(');

                String id = "" + current;
                try {
	                 id = str.substring(5, i).split("=")[0].trim();
                } catch (Exception e) {
                	// ignore
                }
                
                if (i > 0) {
                    str = str.substring(i);
                }
                TreeParser treeParser;

                if (origin != -1) {
                    treeParser = new TreeParser(taxa, str, origin, false);
                } else {
                    try {
                        treeParser = new TreeParser(taxa, str, 0, false);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        treeParser = new TreeParser(taxa, str, 1, false);
                    }
                }

                //if (translationMap != null) treeParser.translateLeafIds(translationMap);
                treeParser.setID(id);
                
                return treeParser;
            }
    		return null;
    	}
    	
    	private void collectTaxaNames(String str) {
    		taxa = new ArrayList<>();
    		int i = 0;
    		while (i < str.length()) {
    			char c = str.charAt(i);
    			switch (c) {
    			case '(':
    			case ')':
    			case ',':
    				// ignore
    				i++;
    				break;
    			case '[':
    				// eat up meta data
    				while (i < str.length() && str.charAt(i) != ']') {
    					i++;
    				}
    				break;
    			case ':':
    				// eat up length
    				while (i < str.length() && !(str.charAt(i) == ')'|| str.charAt(i) == ',')) {
    					i++;
    				}
    				break;
    			default:
    				StringBuilder b = new StringBuilder();
    				boolean done = false;
    				while (i < str.length() && !done) {
    					c = str.charAt(i);
    					done =  c == ')' || c == ':' || c == ',' || c == '(' || c == '[';
    					if (!done) {
    						if (c != '\'' && c != '"') {
    							b.append(c);
    						}
    						i++;
    					} else {
    						taxa.add(b.toString());
    					}
    				}
    				
    			}
    		}		
    	}
    }
    TreeSet treeSet;


    enum Target {
        MAX_CLADE_CREDIBILITY("Maximum clade credibility tree"),
        MAX_SUM_CLADE_CREDIBILITY("Maximum sum of clade credibilities"),
        USER_TARGET_TREE("User target tree");

        String desc;

        Target(String s) {
            desc = s;
        }

        @Override
		public String toString() {
            return desc;
        }
    }

//    enum HeightsSummary {
//        CA_HEIGHTS("Common Ancestor heights"),
//        MEDIAN_HEIGHTS("Median heights"),
//        MEAN_HEIGHTS("Mean heights"),
//        KEEP_HEIGHTS("Keep target heights");
//
//        String desc;
//
//        HeightsSummary(String s) {
//            desc = s;
//        }
//
//        @Override
//		public String toString() {
//            return desc;
//        }
//    }


    // Messages to stderr, output to stdout
    static PrintStream progressStream = Log.err;

//    private final String location1Attribute = "longLat1";
//    private final String location2Attribute = "longLat2";
//    private final String locationOutputAttribute = "location";

    public TreeAnnotator() { }

    public void run(final int burninPercentage,
    					 boolean lowMemory, // allowSingleChild was defunct (always set to false), now replaced by flag to say how much 
                         // HeightsSummary heightsOption,
                         double posteriorLimit,
                         double hpd2D,
                         // Target targetOption,
                         String targetTreeFileName,
                         String inputFileName,
                         String outputFileName
    ) throws IOException  {

        topologySettingService = getTopologySettingService();
        nodeHeightSettingService = getNodeHeightSettingService();

     // Get citations, only print if eihter of them is not empty.
        

        String nodeCitation = nodeHeightSettingService.getCitations();
 

        String topoCitation = topologySettingService.getCitations();
 
        if (!nodeCitation.isBlank() || !topoCitation.isBlank()) {
            Log.warning("======================================================");
            Log.warning("Please cite the following when using the summary tree:\n");
            if (!nodeCitation.isBlank()) {
            	Log.warning(nodeCitation);
            }
            // If both citations are present, print a separator line
            if (!nodeCitation.isBlank() && !topoCitation.isBlank()) {
            	Log.warning("\n-----------------------------------------------------\n");
            }
            if (!topoCitation.isBlank()) {
            	Log.warning(topoCitation);
            }
            Log.warning("======================================================");
 
        }  // Finished printing citations
 

        this.posteriorLimit = posteriorLimit;
        this.hpd2D = hpd2D;

        attributeNames.add("height");
        attributeNames.add("length");

        totalTrees = 10000;
        totalTreesUsed = 0;

        try {
        	if (lowMemory) {
        		treeSet = new MemoryFriendlyTreeSet(inputFileName, burninPercentage);
        	} else {
        		treeSet = new FastTreeSet(inputFileName, burninPercentage);
        	}
        } catch (Exception e) {
        	e.printStackTrace();
        	Log.err.println("Error Parsing Input Tree: " + e.getMessage());
        	return;
        }

        Log.err.println();
        if (!topologySettingService.getServiceName().equals(UserTargetTreeTopologyService.SERVICE_NAME)) {
            // even when a user specified target tree is provided we still need to count the totalTreesUsed for subsequent steps.
            treeSet.reset();
            while (treeSet.hasNext()) {
                Tree tree = treeSet.next();
                tree.getLeafNodeCount();
                if (tree.getDirectAncestorNodeCount() > 0 && !SAmode && processSA) {
                    SAmode = true;
                    Log.err("""
                            [Info] A tree with sampled acnestors was detected.
                                   Turning on sampled ancestor summary analysis.
                            """
                    );
                    if (nodeHeightSettingService.getServiceName().equals("CA")) {
                        throw new RuntimeException("Common ancestor height is unavailable for trees with sampled ancestors.\n" +
                                "Please select a different height summary option.");
                    }
                    if (topologySettingService.getServiceName().contains("CCD")) {
                        Log.err("""
                                [Warning] Sampled ancestors are not directly supported by CCDs.
                                          They will be treated as if they were tips of the tree.
                                          Please interpret the resulting summary tree accordingly.
                                """
                        );
                    }
                }
                totalTreesUsed++;
            }
        }

        Tree targetTree = topologySettingService.setTopology(treeSet, progressStream, this);

     
        cladeSystem = getCladeSystem(targetTree);
        
//        progressStream.println("Collecting node information...");
//        progressStream.println("0              25             50             75            100");
//        progressStream.println("|--------------|--------------|--------------|--------------|");
//
//        int stepSize = Math.max(totalTreesUsed / 60, 1);
//        int reported = 0;
//
//        // this call increments the clade counts and it shouldn't
//        // this is remedied with removeClades call after while loop below
//        cladeSystem = new CladeSystem();
//        cladeSystem.setProcessSA(processSA);
//        cladeSystem.add(targetTree, true);
//        int totalTreesUsedNew = 0;
//        try {
//            int counter = 0;
//            treeSet.reset();
//            while (treeSet.hasNext()) {
//            	Tree tree = treeSet.next();
//            	if (counter == 0) {
//                    setupAttributes(tree);
//            	}
//                cladeSystem.collectAttributes(tree, attributeNames);
//                if (counter > 0 && counter % stepSize == 0 && reported < 61) {
//    				while (1000 * reported < 61000 * (counter + 1)/ this.totalTreesUsed) {
//    	                progressStream.print("*");
//    	                reported++;
//            	    }
//                    progressStream.flush();
//                }
//                totalTreesUsedNew++;
//                counter++;
//        	}
//        	
//            cladeSystem.removeClades(targetTree.getRoot(), true);
//            this.totalTreesUsed = totalTreesUsedNew;
//            cladeSystem.calculateCladeCredibilities(totalTreesUsedNew);
//        } catch (Exception e) {
//            Log.err.println("Error Parsing Input Tree: " + e.getMessage());
//            return;
//        }
//        progressStream.println();
//        progressStream.println();
//
        progressStream.println("Annotating target tree...");

        try {
            annotateTree(cladeSystem, targetTree.getRoot(), null);//, heightsOption);

            nodeHeightSettingService.setNodeHeights(targetTree, progressStream, this);
//            if( heightsOption == HeightsSummary.CA_HEIGHTS ) {
//                setTreeHeightsByCA(targetTree, targetOption);
//            }
        } catch (Exception e) {
        	e.printStackTrace();
            Log.err.println("Error to annotate tree: " + e.getMessage() + "\nPlease check the tree log file format.");
            return;
        }

        progressStream.println("Writing annotated tree....");

        
        processMetaData(targetTree.getRoot());
        try {
            final PrintStream stream = outputFileName != null ?
                    new PrintStream(new FileOutputStream(outputFileName)) :
                    System.out;
            targetTree.init(stream);
            stream.println();
            
            stream.print("tree TREE_" + 
            		topologySettingService.getServiceName() + "_" + 
            		nodeHeightSettingService.getServiceName() + " = ");
            int[] dummy = new int[1];
            String newick = targetTree.getRoot().toSortedNewick(dummy, true);
            stream.print(newick);
            stream.println(";");
//            stream.println(targetTree.getRoot().toShortNewick(false));
//            stream.println();
            targetTree.close(stream);
            stream.println();
        } catch (Exception e) {
            Log.err.println("Error to write annotated tree file: " + e.getMessage());
            return;
        }

    }

    private void processMetaData(Node node) {
		for (Node child : node.getChildren()) {
			processMetaData(child);
		}
		Set<String> metaDataNames = node.getMetaDataNames(); 
		if (metaDataNames != null && !metaDataNames.isEmpty()) {
			String metadata = "";
			for (String name : metaDataNames) {
				Object value = node.getMetaData(name);
				metadata += name + "=";
				if (value instanceof Object[]) {
					Object [] values = (Object[]) value;
					metadata += "{";
					for (int i = 0; i < values.length; i++) {
						metadata += values[i].toString();
						if (i < values.length - 1) {
							metadata += ",";
						}
					}
					metadata += "}";
				} else {
					 metadata += value.toString();
				}
				metadata += ",";
			}
			metadata = metadata.substring(0, metadata.length() - 1);
			node.metaDataString = metadata;
		}		
	}

	private void setupAttributes(Tree tree) {
        for (int i = 0; i < tree.getNodeCount(); i++) {
            Node node = tree.getNode(i);
            Set<String> iter = node.getMetaDataNames();
            if (iter != null) {
            	for (String name : iter) {
                    attributeNames.add(name);
                }
            }
        }

        for (TreeAnnotationPlugin beastObject : beastObjects) {
            Set<String> claimed = beastObject.setAttributeNames(attributeNames);
            attributeNames.removeAll(claimed);
        }
    }

//    private Tree summarizeTrees(CladeSystem cladeSystem, boolean useSumCladeCredibility) throws IOException  {
//
//        Tree bestTree = null;
//        double bestScore = Double.NEGATIVE_INFINITY;
//
//        progressStream.println("Analyzing " + totalTreesUsed + " trees...");
//        progressStream.println("0              25             50             75            100");
//        progressStream.println("|--------------|--------------|--------------|--------------|");
//
//        int stepSize = Math.max(totalTreesUsed / 60, 1);
//        int reported = 0;
//
//        int counter = 0;
//        treeSet.reset();
//        while (treeSet.hasNext()) {
//        	Tree tree = treeSet.next();
//            double score = scoreTree(tree, cladeSystem, useSumCladeCredibility);
//          if (score > bestScore) {
//              bestTree = tree;
//              bestScore = score;
//          }
//		  while (reported < 61 && 1000.0*reported < 61000.0 * (counter + 1) / totalTreesUsed) {
//              progressStream.print("*");
//              reported++;
//              progressStream.flush();
//    	  }
//          counter++;
//        }
//        progressStream.println();
//        progressStream.println();
//        if (useSumCladeCredibility) {
//            progressStream.println("Highest Sum Clade Credibility: " + bestScore);
//        } else {
//            progressStream.println("Highest Log Clade Credibility: " + bestScore);
//        }
//
//        return bestTree;
//    }

//    public double scoreTree(Tree tree, CladeSystem cladeSystem, boolean useSumCladeCredibility) {
//        if (useSumCladeCredibility) {
//            return cladeSystem.getSumCladeCredibility(tree.getRoot(), null);
//        } else {
//            return cladeSystem.getLogCladeCredibility(tree.getRoot(), null);
//        }
//    }


    private void annotateTree(CladeSystem cladeSystem, Node node, BitSet bits) { //, HeightsSummary heightsOption) {

        BitSet bits2 = new BitSet();

        if (node.isLeaf()) {

            int index = cladeSystem.getTaxonIndex(node);
            bits2.set(2*index);

            annotateNode(cladeSystem, node, bits2, true);//, heightsOption);
        } else {

            for (int i = 0; i < node.getChildCount(); i++) {

                Node node1 = node.getChild(i);

                annotateTree(cladeSystem, node1, bits2);//, heightsOption);
            }

            for (int i=1; i<bits2.length(); i=i+2) {
                bits2.set(i, false);
            }
            if (node.isFake() && processSA) {
                int index = cladeSystem.getTaxonIndex(node.getDirectAncestorChild());
                bits2.set(2 * index + 1);
            }

            annotateNode(cladeSystem, node, bits2, false);//, heightsOption);
        }

        if (bits != null) {
            bits.or(bits2);
        }
    }

    private void annotateNode(CladeSystem cladeSystem, Node node, BitSet bits, boolean isTip) {//, HeightsSummary heightsOption) {
        CladeSystem.Clade clade = cladeSystem.getCladeMap().get(bits);
        assert clade != null : "Clade missing?";

        boolean filter = false;
        if (!isTip) {
            final double posterior = clade.getCredibility();
            node.setMetaData("posterior", posterior);
            if (posterior < posteriorLimit) {
                filter = true;
            }
        }

        int i = 0;
        for (String attributeName : attributeNames) {

            if (clade.getAttributeValues() != null && clade.getAttributeValues().size() > 0) {
                double[] values = new double[clade.getAttributeValues().size()];

                HashMap<Object, Integer> hashMap = new HashMap<>();

                Object[] v = clade.getAttributeValues().get(0);
                if (v[i] != null) {

                    final boolean isHeight = attributeName.equals("height");
                    boolean isBoolean = v[i] instanceof Boolean;

                    boolean isDiscrete = v[i] instanceof String;

                    if (forceIntegerToDiscrete && v[i] instanceof Integer) isDiscrete = true;

                    double minValue = Double.MAX_VALUE;
                    double maxValue = -Double.MAX_VALUE;

                    final boolean isArray = v[i] instanceof Object[];
                    boolean isDoubleArray = isArray && ((Object[]) v[i])[0] instanceof Double;
                    // This is Java, friends - first value type does not imply all.
                    if (isDoubleArray) {
                        for (Object n : (Object[]) v[i]) {
                            if (!(n instanceof Double)) {
                                isDoubleArray = false;
                                break;
                            }
                        }
                    }
                    // todo Handle other types of arrays

                    double[][] valuesArray = null;
                    double[] minValueArray = null;
                    double[] maxValueArray = null;
                    int lenArray = 0;

                    if (isDoubleArray) {
                        lenArray = ((Object[]) v[i]).length;

                        valuesArray = new double[lenArray][clade.getAttributeValues().size()];
                        minValueArray = new double[lenArray];
                        maxValueArray = new double[lenArray];

                        for (int k = 0; k < lenArray; k++) {
                            minValueArray[k] = Double.MAX_VALUE;
                            maxValueArray[k] = -Double.MAX_VALUE;
                        }
                    }

                    for (int j = 0; j < clade.getAttributeValues().size(); j++) {
                        Object value = clade.getAttributeValues().get(j)[i];
                        if (isDiscrete) {
                            final Object s = value;
                            if (hashMap.containsKey(s)) {
                                hashMap.put(s, hashMap.get(s) + 1);
                            } else {
                                hashMap.put(s, 1);
                            }
                        } else if (isBoolean) {
                            values[j] = (((Boolean) value) ? 1.0 : 0.0);
                        } else if (isDoubleArray) {
                            // Forcing to Double[] causes a cast exception. MAS
                            try {
                                Object[] array = (Object[]) value;
                                for (int k = 0; k < lenArray; k++) {
                                    valuesArray[k][j] = ((Double) array[k]);
                                    if (valuesArray[k][j] < minValueArray[k]) minValueArray[k] = valuesArray[k][j];
                                    if (valuesArray[k][j] > maxValueArray[k]) maxValueArray[k] = valuesArray[k][j];
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                        } else {
                            // Ignore other (unknown) types
                            if (value instanceof Number) {
                                values[j] = ((Number) value).doubleValue();
                                if (values[j] < minValue) minValue = values[j];
                                if (values[j] > maxValue) maxValue = values[j];
                            }
                        }
                    }
                    if (isHeight) {
                    	nodeHeightSettingService.setNodeHeight(node, values, this);
//                        if (heightsOption == HeightsSummary.MEAN_HEIGHTS) {
//                            final double mean = DiscreteStatistics.mean(values);
//                            if (node.isDirectAncestor()) {
//                                node.getParent().setHeight(mean);
//                            }
//                            if (node.isFake() && processSA) {
//                                node.getDirectAncestorChild().setHeight(mean);
//                            }
//                            node.setHeight(mean);
//                        } else if (heightsOption == HeightsSummary.MEDIAN_HEIGHTS) {
//                            final double median = DiscreteStatistics.median(values);
//                            if (node.isDirectAncestor()) {
//                                node.getParent().setHeight(median);
//                            }
//                            if (node.isFake() && processSA) {
//                                node.getDirectAncestorChild().setHeight(median);
//                            }
//                            node.setHeight(median);
//                        } else {
//                            // keep the existing height
//                        }
                    }

                    if (!filter) {
                        boolean processed = false;
                        for (TreeAnnotationPlugin beastObject : beastObjects) {
                            if (beastObject.handleAttribute(node, attributeName, values)) {
                                processed = true;
                            }
                        }

                        if (!processed) {
                            if (!isDiscrete) {
                                if (!isDoubleArray)
                                    annotateMeanAttribute(node, attributeName, values);
                                else {
                                    for (int k = 0; k < lenArray; k++) {
                                        annotateMeanAttribute(node, attributeName + (k + 1), valuesArray[k]);
                                    }
                                }
                            } else {
                                annotateModeAttribute(node, attributeName, hashMap);
                                annotateFrequencyAttribute(node, attributeName, hashMap);
                            }
                            if (!isBoolean && minValue < maxValue && !isDiscrete && !isDoubleArray) {
                                // Basically, if it is a boolean (0, 1) then we don't need the distribution information
                                // Likewise if it doesn't vary.
                                annotateMedianAttribute(node, attributeName + "_median", values);
                                annotateHPDAttribute(node, attributeName + "_95%_HPD", 0.95, values);
                                annotateRangeAttribute(node, attributeName + "_range", values);
                            }

                            if (isDoubleArray) {
                                String name = attributeName;
                                // todo
//                                    if (name.equals(location1Attribute)) {
//                                        name = locationOutputAttribute;
//                                    }
                                boolean want2d = processBivariateAttributes && lenArray == 2;
                                if (name.equals("dmv")) {  // terrible hack
                                    want2d = false;
                                }
                                for (int k = 0; k < lenArray; k++) {
                                    if (minValueArray[k] < maxValueArray[k]) {
                                        annotateMedianAttribute(node, name + (k + 1) + "_median", valuesArray[k]);
                                        annotateRangeAttribute(node, name + (k + 1) + "_range", valuesArray[k]);
                                        if (!want2d)
                                            annotateHPDAttribute(node, name + (k + 1) + "_95%_HPD", 0.95, valuesArray[k]);
                                    }
                                }
                                // 2D contours
                                if (want2d) {

                                    boolean variationInFirst = (minValueArray[0] < maxValueArray[0]);
                                    boolean variationInSecond = (minValueArray[1] < maxValueArray[1]);

                                    if (variationInFirst && !variationInSecond)
                                        annotateHPDAttribute(node, name + "1" + "_95%_HPD", 0.95, valuesArray[0]);

                                    if (variationInSecond && !variationInFirst)
                                        annotateHPDAttribute(node, name + "2" + "_95%_HPD", 0.95, valuesArray[1]);

                                    if (variationInFirst && variationInSecond)
                                        annotate2DHPDAttribute(node, name, "_" + (int) (100 * hpd2D) + "%HPD", hpd2D, valuesArray);
                                }
                            }
                        }
                    }
                }
            }
            i++;
        }
    }

    public static void annotateMeanAttribute(Node node, String label, double[] values) {
        double mean = DiscreteStatistics.mean(values);
        node.setMetaData(label, mean);
    }

    public static void annotateMedianAttribute(Node node, String label, double[] values) {
        double median = DiscreteStatistics.median(values);
        node.setMetaData(label, median);

    }

    public static void annotateModeAttribute(Node node, String label, HashMap<Object, Integer> values) {
        Object mode = null;
        int maxCount = 0;
        int totalCount = 0;
        int countInMode = 1;

        for (Object key : values.keySet()) {
            int thisCount = values.get(key);
            if (thisCount == maxCount) {
                // I hope this is the intention
                mode = mode.toString().concat("+" + key);
                countInMode++;
            } else if (thisCount > maxCount) {
                mode = key;
                maxCount = thisCount;
                countInMode = 1;
            }
            totalCount += thisCount;
        }
        double freq = (double) maxCount / (double) totalCount * countInMode;
        node.setMetaData(label, mode);
        node.setMetaData(label + ".prob", freq);
    }

    public static void annotateFrequencyAttribute(Node node, String label, HashMap<Object, Integer> values) {
        double totalCount = 0;
        Set<?> keySet = values.keySet();
        int length = keySet.size();
        String[] name = new String[length];
        Double[] freq = new Double[length];
        int index = 0;
        for (Object key : values.keySet()) {
            name[index] = key.toString();
            freq[index] = Double.valueOf(values.get(key));
            totalCount += freq[index];
            index++;
        }
        for (int i = 0; i < length; i++)
            freq[i] /= totalCount;

        node.setMetaData(label + ".set", name);
        node.setMetaData(label + ".set.prob", freq);
    }

    public static void annotateRangeAttribute(Node node, String label, double[] values) {
        double min = DiscreteStatistics.min(values);
        double max = DiscreteStatistics.max(values);
        node.setMetaData(label, new Object[]{min, max});
    }

    public static void annotateHPDAttribute(Node node, String label, double hpd, double[] values) {
        int[] indices = new int[values.length];
        HeapSort.sort(values, indices);

        double minRange = Double.MAX_VALUE;
        int hpdIndex = 0;

        int diff = (int) Math.round(hpd * values.length);
        for (int i = 0; i <= (values.length - diff); i++) {
            double minValue = values[indices[i]];
            double maxValue = values[indices[i + diff - 1]];
            double range = Math.abs(maxValue - minValue);
            if (range < minRange) {
                minRange = range;
                hpdIndex = i;
            }
        }
        double lower = values[indices[hpdIndex]];
        double upper = values[indices[hpdIndex + diff - 1]];
        node.setMetaData(label, new Object[]{lower, upper});
    }



    public static final String CORDINATE = "cordinates";

    private String formattedLocation(double x) {
        return String.format("%5.2f", x);
    }

    private void annotate2DHPDAttribute(Node node, String preLabel, String postLabel,
                                        double hpd, double[][] values) {
        if (USE_R) {


        } else { // do not use R
            boolean bandwidthLimit = false;

            ContourMaker kde = new ContourWithSynder(values[0], values[1], bandwidthLimit);

            ContourPath[] paths = kde.getContourPaths(hpd);

            node.setMetaData(preLabel + postLabel + "_modality", paths.length);

            if (paths.length > 1) {
                Log.err.println("Warning: a node has a disjoint " + 100 * hpd + "% HPD region.  This may be an artifact!");
                Log.err.println("Try decreasing the enclosed mass or increasing the number of samples.");
            }

            StringBuffer output = new StringBuffer();
            int i = 0;
            for (ContourPath p : paths) {
                output.append("\n<" + CORDINATE + ">\n");
                double[] xList = p.getAllX();
                double[] yList = p.getAllY();
                StringBuffer xString = new StringBuffer("{");
                StringBuffer yString = new StringBuffer("{");
                for (int k = 0; k < xList.length; k++) {
                    xString.append(formattedLocation(xList[k])).append(",");
                    yString.append(formattedLocation(yList[k])).append(",");
                }
                xString.append(formattedLocation(xList[0])).append("}");
                yString.append(formattedLocation(yList[0])).append("}");

                node.setMetaData(preLabel + "1" + postLabel + "_" + (i + 1), xString);
                node.setMetaData(preLabel + "2" + postLabel + "_" + (i + 1), yString);
                i++;

            }
        }
    }

    int totalTrees = 0;
    int totalTreesUsed = 0;
    double posteriorLimit = 0.0;
    double hpd2D = 0.80;

    private final List<TreeAnnotationPlugin> beastObjects = new ArrayList<>();

    Set<String> attributeNames = new HashSet<>();
    TaxonSet taxa = null;

    static boolean processBivariateAttributes = true;
    
    public static void printTitle() {
        progressStream.println();
        centreLine("TreeAnnotator " + version.getVersionString() + ", " + version.getDateString(), 60);
        centreLine("MCMC Output analysis", 60);
        centreLine("by", 60);
        centreLine("Andrew Rambaut and Alexei J. Drummond", 60);
        progressStream.println();
        centreLine("Institute of Evolutionary Biology", 60);
        centreLine("University of Edinburgh", 60);
        centreLine("a.rambaut@ed.ac.uk", 60);
        progressStream.println();
        centreLine("Department of Computer Science", 60);
        centreLine("University of Auckland", 60);
        centreLine("alexei@cs.auckland.ac.nz", 60);
        progressStream.println();
        progressStream.println();
    }

    public static void centreLine(String line, int pageWidth) {
        int n = pageWidth - line.length();
        int n1 = n / 2;
        for (int i = 0; i < n1; i++) {
            progressStream.print(" ");
        }
        progressStream.println(line);
    }


//    public static void printUsage(Arguments arguments) {
//
//        arguments.printUsage("treeannotator", "<input-file-name> [<output-file-name>]");
//        progressStream.println();
//        progressStream.println("  Example: treeannotator test.trees out.txt");
//        progressStream.println("  Example: treeannotator -burnin 10 -heights mean test.trees out.txt");
//        progressStream.println("  Example: treeannotator -burnin 20 -target map.tree test.trees out.txt");
//        progressStream.println();
//    }
//
//    static private Controller controller;
//
//    @Override
//	protected void createDialog() {		
//		PrintStream err = System.err;
//		System.setErr(new PrintStream(new OutputStream() {
//		    public void write(int b) {
//		    }
//		}));
//		System.setErr(err);
//
//        java.net.URL url = TreeAnnotator2.class.getClassLoader().getResource("../tools/images/utility.png");
//
//        final String versionString = version.getVersionString();
//        String nameString = "TreeAnnotator " + versionString;
//        String aboutString = "<html><center><p>" + versionString + ", " + version.getDateString() + "</p>" +
//                "<p>by<br>" +
//                "Andrew Rambaut and Alexei J. Drummond</p>" +
//                "<p>Institute of Evolutionary Biology, University of Edinburgh<br>" +
//                "<a href=\"mailto:a.rambaut@ed.ac.uk\">a.rambaut@ed.ac.uk</a></p>" +
//                "<p>Department of Computer Science, University of Auckland<br>" +
//                "<a href=\"mailto:alexei@cs.auckland.ac.nz\">alexei@cs.auckland.ac.nz</a></p>" +
//                "<p>Part of the BEAST package:<br>" +
//                "<a href=\"http://beast.bio.ed.ac.uk/\">http://beast.bio.ed.ac.uk/</a></p>" +
//                "</center></html>";
//
//        Log.info = System.out;
//        Log.err = System.err;
//
//        // The ConsoleApplication will have overridden System.out so set progressStream
//        // to capture the output to the window:
//        // new beastfx.app.util.Console();            
//        progressStream = System.out;
//
//        printTitle();
//        
//	        	try {
//					Dialog<String> dialog = new Dialog<>();
//				    dialog.setTitle("TreeAnnotator " + BEASTVersion.INSTANCE.getVersion());
//				    FXMLLoader fl = new FXMLLoader();
//				    fl.setClassLoader(getClass().getClassLoader());
//				    fl.setLocation(TreeAnnotator2.class.getResource("TreeAnnotator.fxml"));
//				    DialogPane root = fl.load();
//				    dialog.setDialogPane(root);
//			
//				    ButtonType run = new ButtonType("Run", ButtonData.OK_DONE);
//				    dialog.getDialogPane().getButtonTypes().add(run);
//				    ButtonType cancel = new ButtonType("Quit", ButtonData.CANCEL_CLOSE);
//				    dialog.getDialogPane().getButtonTypes().add(cancel);
//				    ThemeProvider.loadStyleSheet(dialog.getDialogPane().getScene());
//				    
//				    
//					//Showing the dialog on clicking the button
//			        Optional<String> result = dialog.showAndWait();
//			        dialog.close();
//			        
//			        Object o = result.get();
//			        String str = o.toString();
//			        
//				    if (str.equals(run.toString())) {
//					    controller = fl.getController();
//			        	controller.run(null);
//			        } else {
//			        	Log.warning("Quiting TreeAnnotator");
//			        	System.exit(0);
//			    		return;
//			        }
//
//					new Thread() {
//			public void run() {
//		        int burninPercentage = controller.getBurninPercentage();
//		        if (burninPercentage < 0) {
//		        	Log.warning.println("burnin percentage is " + burninPercentage + " but should be non-negative. Setting it to zero");
//		        	burninPercentage = 0;
//		        }
//		        if (burninPercentage >= 100) {
//		        	Log.err.println("burnin percentage is " + burninPercentage + " but should be less than 100.");
//		        	return;
//		        }
//		        double posteriorLimit = controller.getPosteriorLimit();
//		        double hpd2D = 0.80;
//		        Target targetOption = controller.getTargetOption();
//		        HeightsSummary heightsOption = controller.getHeightsOption();
//
//		        String targetTreeFileName = controller.getTargetFileName();
//		        if (targetOption == Target.USER_TARGET_TREE && targetTreeFileName == null) {
//		            Log.err.println("No target file specified");
//		            return;
//		        }
//
//		        String inputFileName = controller.getInputFileName();
//		        if (inputFileName == null) {
//		            Log.err.println("No input file specified");
//		            return;
//		        }
//
//		        String outputFileName = controller.getOutputFileName();
//		        if (outputFileName == null) {
//		            Log.err.println("No output file specified");
//		            return;
//		        }
//		    	boolean lowMem = controller.useLowMem();
//
//		        try {
//		            new TreeAnnotator2(burninPercentage,
//		            		lowMem,
//		                    heightsOption,
//		                    posteriorLimit,
//		                    hpd2D,
//		                    targetOption,
//		                    targetTreeFileName,
//		                    inputFileName,
//		                    outputFileName);
//
//		        } catch (Exception ex) {
//		            Log.err.println("Exception: " + ex.getMessage());
//		        }
//
//		        progressStream.println("Finished - Quit program to exit.");
//			}
//		}.start();
//
//
//        } catch (IOException e) {
//        	e.printStackTrace();
//        }
//    }
//


    //Main method
    public static void main(String[] args) throws Exception {
        // There is a major issue with languages that use the comma as a decimal separator.
        // To ensure compatibility between programs in the package, enforce the US locale.
        Locale.setDefault(Locale.US);
        
        PackageManager.loadExternalJars();
        
        if (args.length == 0) {
        	// show GUI
        	TAGui.launch(TAGui.class, args);
        	return;
        }
        
        try {
	        TreeAnnotator annotator = new TreeAnnotator();
	        Application app = new Application(annotator);
	        annotator.filesInput.determineClass(annotator);
	        app.setDefaultInput(annotator.filesInput);
	        app.parseArgs(args, true);
	        annotator.initAndValidate();
	        annotator.run();
        } catch (OutOfMemoryError e) {
        	Log.warning("TreeAnnotator ran out of memory: " + e.getMessage());
        	Log.warning("You can subsample the tree set with LogCombiner or provide more memory to fix this.");
        	Log.warning("See https://www.beast2.org/increasing-memory-usage/ on how to provide more memory");
        }
    }

	@Override
    public void initAndValidate() {
    	
    }
    
    @Override
	public void run() throws Exception {

    	for (String versionFile : versionFileInput.get()) {
    		BEASTClassLoader.addServices(versionFile);
    	}
    	
        String targetTreeFileName = null;
        String inputFileName = null;
        String outputFileName = null;


        printTitle();

        if (nohpd2DInput.get() != null && nohpd2DInput.get()) {
        	processBivariateAttributes = false;
        }
        
        if (noSAInput.get() != null && noSAInput.get()) {
        	processSA = false;
        }

        if (forceDiscreteInput.get() != null && forceDiscreteInput.get()) {
            Log.info.println("  Forcing integer traits to be treated as discrete traits.");
            forceIntegerToDiscrete = true;
        }

//        if (arguments.hasOption("help")) {
//            printUsage(arguments);
//            System.exit(0);
//        }
        
        boolean lowMem = false;
        if (lowMemInput.get() != null && lowMemInput.get()) {
        	lowMem = true;
        }

            		
//        HeightsSummary heights = HeightsSummary.CA_HEIGHTS;
//        if (arguments.hasOption("heights")) {
//            String value = arguments.getStringOption("heights");
//            if (value.equalsIgnoreCase("mean")) {
//                heights = HeightsSummary.MEAN_HEIGHTS;
//            } else if (value.equalsIgnoreCase("median")) {
//                heights = HeightsSummary.MEDIAN_HEIGHTS;
//            } else if (value.equalsIgnoreCase("keep")) {
//                heights = HeightsSummary.KEEP_HEIGHTS;
//            } else if (value.equalsIgnoreCase("ca")) {
//                heights = HeightsSummary.CA_HEIGHTS;
//            }
//        }
//        if (heights == HeightsSummary.CA_HEIGHTS) {
//            Log.warning.println("Please cite: Heled and Bouckaert: Looking for trees in the forest:\n" +
//                    "summary tree from posterior samples. BMC Evolutionary Biology 2013 13:221.");
//        }
        
        int burninPercentage = burnInPercentageInput.get();
        if (burninPercentage >= 100) {
        	Log.err.println("burnin percentage is " + burninPercentage + " but should be less than 100.");
        	System.exit(1);
        }

        double posteriorLimit = 0.0;
        if (limitInput.get() != null) {
            posteriorLimit = limitInput.get();
        }

        double hpd2D = 0.80;
        if (hpd2DInput.get() != null) {
            hpd2D = hpd2DInput.get();
            if (hpd2D <= 0 || hpd2D >=1) {
            	Log.err.println("hpd2D is a fraction and should be in between 0.0 and 1.0.");
            	System.exit(1);            	
            }
            processBivariateAttributes = true;
        }


//        Target target = Target.MAX_CLADE_CREDIBILITY;
//        if (targetInput.get() != null) {
//            target = Target.USER_TARGET_TREE;
//            targetTreeFileName = arguments.getStringOption("target");
//        }

        final List<File> args2 = filesInput.get();
        		

        switch (args2.size()) {
            case 2:
                outputFileName = args2.get(1).getPath();
                // fall to
            case 1:
                inputFileName = args2.get(0).getPath();
                break;
            default: {
                Log.err.println("\nCommand not understood: " + args2.get(2).getPath() + "\n");
                Log.err.println();
            	new Application(new TreeAnnotator(), "Tree Annotator", new String[] {"-help"});
                System.exit(1);
            }
        }
        
        try {
        	run(burninPercentage, lowMem, posteriorLimit, hpd2D, targetTreeFileName, inputFileName, outputFileName);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Common ancestor height")) {
                Log.err("[Error]:\n  " + msg.replace("\n", "\n  "));
            } else {
                e.printStackTrace();
            }
        }

//        if (args.length == 0) {
//        	// only need exit when in GUI mode
//        	System.exit(0);
//        }
    }

    private TopologySettingService getTopologySettingService() {
    	String topology = topologyInput.get();
    	Set<String> nodeTopologySettingServices = BEASTClassLoader.loadService(TopologySettingService.class);
    	String topologyServiceNames = "";
        for (String str : nodeTopologySettingServices) {
            try {
            	TopologySettingService nodeTopologySettingService = (TopologySettingService) BEASTClassLoader.forName(str).newInstance();
            	if (nodeTopologySettingService.getServiceName().equals(topology) || nodeTopologySettingService.getDescription().equals(topology)) {
            		return nodeTopologySettingService;
            	}
            	if (topologyServiceNames.length() > 1) {
            		topologyServiceNames += ",";
            	}
            	topologyServiceNames += nodeTopologySettingService.getServiceName();
            } catch (Throwable e) {
                Log.debug("Failed to discover " + str + " " + e.getMessage() + " "
                		+ "Choose one of these: " + topologyServiceNames);            	
            }
        }
        throw new IllegalArgumentException("Could not find method for setting topology with name " + topology
        	+ ". Choose one of these: " + topologyServiceNames);	
	}

	private NodeHeightSettingService getNodeHeightSettingService() {
        String heights = heightInput.get();
    	Set<String> nodeHeightSettingServices = BEASTClassLoader.loadService(NodeHeightSettingService.class);
    	String heightServiceNames = "";
        for (String str : nodeHeightSettingServices) {
            try {
            	NodeHeightSettingService nodeHeightSettingService = (NodeHeightSettingService) BEASTClassLoader.forName(str).newInstance();
            	if (nodeHeightSettingService.getServiceName().equals(heights) || nodeHeightSettingService.getDescription().equals(heights)) {
            		return nodeHeightSettingService;
            	}
            	if (heightServiceNames.length() > 1) {
            		heightServiceNames += ",";
            	}
            	heightServiceNames += nodeHeightSettingService.getServiceName();
            } catch (Throwable e) {
                Log.debug("Failed to discover " + str + " " + e.getMessage() + " "
                		+ "Choose one of these: " + heightServiceNames);            	
            }
        }
        throw new IllegalArgumentException("Could not find method for setting node height with name " + heights 
        		+ ". Choose one of these: " + heightServiceNames);	
	}

	/**
     * @author Andrew Rambaut
     * @version $Id$
     */
    //TODO code review: it seems not necessary
    public static interface TreeAnnotationPlugin {
        Set<String> setAttributeNames(Set<String> attributeNames);

        boolean handleAttribute(Node node, String attributeName, double[] values);
    }

//    boolean setTreeHeightsByCA(Tree targetTree, Target targetOption) throws IOException
//             {
//        progressStream.println("Setting node heights...");
//        progressStream.println("0              25             50             75            100");
//        progressStream.println("|--------------|--------------|--------------|--------------|");
//
//        int reportStepSize = totalTreesUsed / 60;
//        if (reportStepSize < 1) reportStepSize = 1;
//        int reported = 0;
//
//
//        // this call increments the clade counts and it shouldn't
//        // this is remedied with removeClades call after while loop below
//        CladeSystem cladeSystem = new CladeSystem(targetTree);
//        final int clades = cladeSystem.getCladeMap().size();
//
//        // allocate posterior tree nodes order once
//        int[] postOrderList = new int[clades];
//        BitSet[] ctarget = new BitSet[clades];
//        BitSet[] ctree = new BitSet[clades];
//
//        for (int k = 0; k < clades; ++k) {
//            ctarget[k] = new BitSet();
//            ctree[k] = new BitSet();
//        }
//
//        cladeSystem.getTreeCladeCodes(targetTree, ctarget);
//
//        // temp collecting heights inside loop allocated once
//        double[][] hs = new double[clades][treeSet.totalTrees - treeSet.burninCount];
//
//        // heights total sum from posterior trees
//        double[] ths = new double[clades];
//
//        int totalTreesUsed = 0;
//
//        int counter = 0;
//        treeSet.reset();
//        while (treeSet.hasNext()) {
//        	Tree tree = treeSet.next();
//            TreeUtils.preOrderTraversalList(tree, postOrderList);
//            cladeSystem.getTreeCladeCodes(tree, ctree);
//            for (int k = 0; k < clades; ++k) {
//                int j = postOrderList[k];
//                for (int i = 0; i < clades; ++i) {
//                    if( CollectionUtils.isSubSet(ctarget[i], ctree[j]) ) {
//                        hs[i][counter] = tree.getNode(j).getHeight();
//                    }
//                }
//            }
//            for (int k = 0; k < clades; ++k) {
//                ths[k] += hs[k][counter];
//            }
//            totalTreesUsed += 1;
//			while (reported < 61 && 1000.0*reported < 61000.0 * (counter + 1) / this.totalTreesUsed) {
//		          progressStream.print("*");
//		          reported++;
//		          progressStream.flush();
//			}
//            counter++;
//
//        }
//
//        if (targetOption != Target.USER_TARGET_TREE)
//            targetTree.initAndValidate();
//
//        cladeSystem.removeClades(targetTree.getRoot(), true);
//        for (int k = 0; k < clades; ++k) {
//            ths[k] /= totalTreesUsed;
//            final Node node = targetTree.getNode(k);
//            node.setHeight(ths[k]);
//            String attributeName = "CAheight";
//            double [] values = hs[k];
//            double min = values[0];
//            double max = values[0];
//            for (double d : values) {
//            	min = Math.min(d, min);
//            	max = Math.max(d, max);
//            }
//            if (Math.abs(min - max) > 1e-10) {
//	            annotateMeanAttribute(node, attributeName + "_mean", values);
//	            annotateMedianAttribute(node, attributeName + "_median", values);
//	            annotateHPDAttribute(node, attributeName + "_95%_HPD", 0.95, values);
//	            annotateRangeAttribute(node, attributeName + "_range", values);
//            }
//        }
//
//        assert (totalTreesUsed == this.totalTreesUsed);
//        this.totalTreesUsed = totalTreesUsed;
//        progressStream.println();
//        progressStream.println();
//
//        return true;
//    }

    private CladeSystem cladeSystem = null;

    // todo is this function irrelevant?
	public CladeSystem getCladeSystem() {
		burninPercentage = burnInPercentageInput.get();
        CladeSystem cladeSystem = new CladeSystem();
	    try {
	    	treeSet.reset();
	    	totalTreesUsed = 0;
	        cladeSystem.setProcessSA(false);
	    	while (treeSet.hasNext()) {
	    		Tree tree = treeSet.next();
	            tree.getLeafNodeCount();
	            if (tree.getDirectAncestorNodeCount() > 0 && !SAmode && processSA) {
	                SAmode = true;
	                Log.err.println("A tree with a sampled ancestor is found. Turning on\n the sampled ancestor " +
	                        "summary analysis.");
	                if (nodeHeightSettingService.getServiceName().equals("CA")) {
                        throw new RuntimeException("Common ancestor height is unavailable for trees with sampled ancestors.\n" +
                                "Please select a different height summary option.");
	                }
	                cladeSystem.setProcessSA(true);
	            }
	        	cladeSystem.add(tree, false);
	            totalTreesUsed++;
	        }
	        totalTrees = totalTreesUsed * 100 / (100-Math.max(burninPercentage, 0));
	    } catch (Exception e) {
	    	Log.err.println(e.getMessage());
	        return null;
	    }
	
	    progressStream.println();
	    progressStream.println();
	
	    if (totalTrees < 1) {
	    	Log.err.println("No trees");
	        return null;
	    }
	    if (totalTreesUsed <= 1) {
	        if (burninPercentage > 0) {
	            Log.err.println("No trees to use: burnin too high");
	            return null;
	        }
	    }
	    cladeSystem.calculateCladeCredibilities(totalTreesUsed);
	
	    progressStream.println("Total number of trees " + totalTrees + ", where " + totalTreesUsed + " are used.");
	
	    progressStream.println("Total unique clades: " + cladeSystem.getCladeMap().keySet().size());
	    progressStream.println();
	    return cladeSystem;
	}

	public CladeSystem getCladeSystem(Tree targetTree) {
    	if (cladeSystem != null) {
    		return cladeSystem;
    	}
        progressStream.println("Collecting node information...");
        progressStream.println("0              25             50             75            100");
        progressStream.println("|--------------|--------------|--------------|--------------|");

        int stepSize = Math.max(totalTreesUsed / 60, 1);
        int reported = 0;

        // this call increments the clade counts and it shouldn't
        // this is remedied with removeClades call after while loop below
        cladeSystem = new CladeSystem();
        cladeSystem.setProcessSA(processSA);
        cladeSystem.add(targetTree, true);
        int totalTreesUsedNew = 0;
        try {
            int counter = 0;
            treeSet.reset();
            while (treeSet.hasNext()) {
            	Tree tree = treeSet.next();
            	if (counter == 0) {
                    setupAttributes(tree);
            	}
                cladeSystem.collectAttributes(tree, attributeNames);
    			while (reported < 61 && 1000.0*reported < 61000.0 * (counter + 1) / this.totalTreesUsed) {
  		          	progressStream.print("*");
  		          	reported++;
  		          	progressStream.flush();
    			}
                totalTreesUsedNew++;
                counter++;
        	}
        	
            cladeSystem.removeClades(targetTree.getRoot(), true);
            this.totalTreesUsed = totalTreesUsedNew;
            cladeSystem.calculateCladeCredibilities(totalTreesUsedNew);
        } catch (Exception e) {
            Log.err.println("Error Parsing Input Tree: " + e.getMessage());
            return null;
        }
        progressStream.println();
        progressStream.println();
        return cladeSystem;
    }
    
    public int getTotalTreesUsed() {return totalTreesUsed;}
    public TreeSet getTreeSet() {return treeSet;}
    public boolean isProcessSA() {return processSA;}
    public int getTotalTrees() {return totalTrees;}
    public int getBurninCount() {return burnInPercentageInput.get();}


}

