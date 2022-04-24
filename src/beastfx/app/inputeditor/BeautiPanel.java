package beastfx.app.inputeditor;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Rectangle;
import java.lang.reflect.InvocationTargetException;

import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;

import javax.swing.JList;
import beastfx.app.util.Alert;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

// import beastfx.app.inputeditor.BeautiDoc;
//import beastfx.app.inputeditor.BeautiDocProvider;
//import beastfx.app.inputeditor.BeautiPanelConfig;
//import beastfx.app.inputeditor.InputEditor;
import beastfx.app.inputeditor.BeautiPanelConfig.Partition;
import beastfx.app.inputeditor.InputEditor.ExpandOption;
import beast.app.util.Utils;
import beast.base.core.BEASTInterface;
import beast.base.core.Input;
import beast.base.evolution.alignment.Taxon;
import beast.base.evolution.branchratemodel.BranchRateModel;
import beast.base.evolution.likelihood.GenericTreeLikelihood;
import beast.base.evolution.sitemodel.SiteModel;
import beast.base.evolution.sitemodel.SiteModelInterface;
import beast.base.evolution.tree.TreeInterface;
import beast.base.inference.CompoundDistribution;
import beast.base.inference.MCMC;
import beast.base.parser.PartitionContext;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;

/**
 * panel making up each of the tabs in Beauti *
 */
public class BeautiPanel extends Pane implements ListSelectionListener, BeautiDocProvider {

    private static final long serialVersionUID = 1L;
    public final static String ICONPATH = "beastfx.app.beauti/";

    static int partitionListPreferredWidth = 120;

    private SplitPane splitPane;

    /**
     * document that this panel applies to *
     */
    BeautiDoc doc;

    @Override
    public BeautiDoc getDoc() {
        return doc;
    }
    
    @Override
    public BeautiPanelConfig getConfig() {
    	return config;
    }
    
    @Override
    public int getPartitionIndex() {
    	return partitionIndex;    			
    }
    
    /**
     * configuration for this panel *
     */
    public BeautiPanelConfig config;

    /**
     * panel number *
     */
    int panelIndex;

    /**
     * partition currently on display *
     */
    public int partitionIndex = 0;

    /**
     * box containing the list of partitions, to make (in)visible on update *
     */
    BorderPane partitionComponent;
    BorderPane pane;
    /**
     * list of partitions in m_listBox *
     */
    ListView<String> listOfPartitions;
    /**
     * model for m_listOfPartitions *
     */
    DefaultListModel<String> listModel;

    ScrollPane scroller;

    /**
     * component containing main input editor *
     */
    Component centralComponent = null;

    public BeautiPanel() {
    }

    public BeautiPanel(int panelIndex, BeautiDoc doc, BeautiPanelConfig config) throws NoSuchMethodException, SecurityException, ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        this.doc = doc;
        this.panelIndex = panelIndex;
        pane = new BorderPane();
        //setLayout(new BorderLayout());

        this.config = config;
        if (this.config.hasPartition() != Partition.none &&
                doc.getPartitions(config.hasPartitionsInput.get().toString()).size() > 1) {
            splitPane = new SplitPane();
            splitPane.setJSplitPane.HORIZONTAL_SPLIT);
            pane.setCenter(splitPane);
        } else {
            splitPane = null;
        }

        refreshPanel();
        addPartitionPanel(this.config.hasPartition(), panelIndex);

        //setOpaque(false);
        
    } // c'tor

    void addPartitionPanel(Partition hasPartition, int panelIndex) {
        VBox box = new VBox();
        if (splitPane != null && hasPartition != Partition.none) {
            box.getChildren().add(createList());
        } else {
            return;
        }
        box.getChildren().add(new Separator(Orientation.VERTICAL));
        box.getChildren().add(new Label(getIcon(panelIndex, config)));

        splitPane.getChildren().add(box, JSplitPane.LEFT);
        if (listOfPartitions != null) {
            listOfPartitions.setSelectedIndex(partitionIndex);
        }
    }
    
	private ImageIcon getIcon(int panelIndex, BeautiPanelConfig config) {
		String iconLocation = BeautiPanel.ICONPATH + panelIndex + ".png";
		if (config != null) {
			iconLocation = BeautiPanel.ICONPATH + config.getIcon();
		}
		return Utils.getIcon(iconLocation);
	}


    /**
     * Create a list of partitions and return as a JComponent;
     * @return
     */
    Node createList() {
        partitionComponent = new BorderPane();
        // partitionComponent.setLayout(new BorderLayout());
        Label partitionLabel = new Label("Partition");
        partitionLabel.setHorizontalAlignment(SwingConstants.CENTER);
        partitionComponent.getChildren().add(partitionLabel, BorderLayout.NORTH);
        listModel = new DefaultListModel<>();
        listOfPartitions = new JList<>(listModel);
        listOfPartitions.setId("listOfPartitions");
        listOfPartitions.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        Dimension size = new Dimension(partitionListPreferredWidth, 300);
        //listOfPartitions.setFixedCellWidth(120);
//    	m_listOfPartitions.setSize(size);
        //listOfPartitions.setPrefSize(size);
    	listOfPartitions.setMinSize(size.getWidth(), size.getHeight());
//    	m_listOfPartitions.setBounds(0, 0, 100, 100);

        listOfPartitions.addListSelectionListener(this);
        updateList();

        // AJD: This is unnecessary and not appropriate for Mac OS X look and feel
        //listOfPartitions.setBorder(new BevelBorder(BevelBorder.RAISED));

        ScrollPane listPane = new ScrollPane();
        listPane.setContent(listOfPartitions);
        partitionComponent.getChildren().add(listPane, BorderLayout.CENTER);
        // AJD: This is unnecessary and not appropriate for Mac OS X look and feel
        //partitionComponent.setBorder(new EtchedBorder());
        return partitionComponent;
    }

    public void updateList() {
        if (listModel == null) {
            return;
        }
        listModel.clear();
        if (listModel.size() > 0) {
            // this is a weird bit of code, since listModel.clear should ensure that size()==0, but it doesn't
            return;
        }
        String type = config.hasPartitionsInput.get().toString();
        for (BEASTInterface partition : doc.getPartitions(type)) {
        	if (type.equals("SiteModel")) {
        		partition = (BEASTInterface) ((GenericTreeLikelihood) partition).siteModelInput.get();
        	} else if (type.equals("ClockModel")) {
        		partition = ((GenericTreeLikelihood) partition).branchRateModelInput.get();
        	} else if (type.equals("Tree")) {
        		partition = (BEASTInterface) ((GenericTreeLikelihood) partition).treeInput.get();
        	}
            String partitionID = partition.getID();
            partitionID = partitionID.substring(partitionID.lastIndexOf('.') + 1);
            if (partitionID.length() > 1 && partitionID.charAt(1) == ':') {
            	partitionID = partitionID.substring(2);
            }
            listModel.addElement(partitionID);
        }
        if (partitionIndex >= 0 && listModel.size() > 0)
            listOfPartitions.setSelectedIndex(partitionIndex);
    }

    

    // AR remove globals (doesn't seem to be used anywhere)...
//	static BeautiPanel g_currentPanel = null;

    public void refreshPanel() throws NoSuchMethodException, SecurityException, ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (doc.alignments.size() == 0) {
            refreshInputPanel();
            return;
        }
        doc.scrubAll(true, false);

        // toggle splitpane
        if (splitPane == null && config.hasPartition() != Partition.none &&
                doc.getPartitions(config.hasPartitionsInput.get().toString()).size() > 1) {
            splitPane = new SplitPane(JSplitPane.HORIZONTAL_SPLIT);
            pane.setCenter(splitPane);
            addPartitionPanel(config.hasPartition(), panelIndex);
        }
        if (splitPane != null && (config.hasPartition() == Partition.none ||
                doc.getPartitions(config.hasPartitionsInput.get().toString()).size() <= 1)) {
            remove(splitPane);
            splitPane = null;
        }

        refreshInputPanel();
        if (partitionComponent != null && config.getType() != null) {
            partitionComponent.setVisible(doc.getPartitions(config.getType()).size() > 1);
        }

//		g_currentPanel = this;
    }
    
    void refreshInputPanel(BEASTInterface beastObject, Input<?> input, boolean addButtons, InputEditor.ExpandOption forceExpansion) throws NoSuchMethodException, SecurityException, ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        if (centralComponent != null) {
            remove(centralComponent);
        }
        if (input != null && input.get() != null && input.getType() != null) {
            InputEditor.ButtonStatus bs = config.buttonStatusInput.get();
            InputEditor inputEditor = doc.getInputEditorFactory().createInputEditor(input, beastObject, addButtons, forceExpansion, bs, null, doc);

            JPanel p = new JPanel();
            p.setLayout(new BorderLayout());
            if (isToClone()) {
                ClonePartitionPanel clonePartitionPanel = new ClonePartitionPanel(this);
                p.add(clonePartitionPanel, BorderLayout.NORTH);
            } else {
                p.add(inputEditor.getComponent(), BorderLayout.CENTER);
            }

            Rectangle bounds = new Rectangle(0,0);
            if (scroller != null) {
            	// get lastPaintPosition from viewport
            	// HACK access it through its string representation
	            JViewport v = scroller.getViewport();
	            String vs = v.toString();
	            int i = vs.indexOf("lastPaintPosition=java.awt.Point[x=");
	            if (i > -1) {
	            	i = vs.indexOf("y=", i);
	            	vs = vs.substring(i+2, vs.indexOf("]", i));
	            	i = Integer.parseInt(vs);
	            } else {
	            	i = 0;
	            }
	            bounds.y = -i;
            }
            scroller = new JScrollPane(p);
            scroller.getViewport().scrollRectToVisible(bounds);
            centralComponent = scroller;
        } else {
            centralComponent = new Label("No input editors.");
        }
        if (splitPane != null) {
            JPanel panel = new JPanel();
            panel.setLayout(new BorderLayout());
            panel.add(centralComponent, BorderLayout.NORTH);
            splitPane.add(panel, JSplitPane.RIGHT);
        } else {
            add(centralComponent);
        }
    }

    void refreshInputPanel() throws NoSuchMethodException, SecurityException, ClassNotFoundException, InstantiationException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
        doc.currentInputEditors.clear();
        InputEditor.Base.g_nLabelWidth = config.labelWidthInput.get();
        BEASTInterface beastObject = config;
        final Input<?> input = config.resolveInput(doc, partitionIndex);

        boolean addButtons = config.addButtons();
        ExpandOption forceExpansion = config.forceExpansion();
        refreshInputPanel(beastObject, input, addButtons, forceExpansion);
    }

    /** 
     * Clones partition identified by sourceID to targetID and type (Site/Clock/Tree model)
     * as stored in config.
     * @param sourceID
     * @param targetID
     */
    public void cloneFrom(String sourceID, String targetID) {
    	if (sourceID.equals(targetID)) {
    		return;
    	}

    	String type = config.hasPartitionsInput.get().toString();
    	java.util.List<BEASTInterface> list = doc.getPartitions(type);
    	int source = -1, target = -1;
        for (int i = 0; i < list.size(); i++) {
        	BEASTInterface partition = list.get(i);
        	if (type.equals("SiteModel")) {
        		partition = (BEASTInterface) ((GenericTreeLikelihood) partition).siteModelInput.get();
        	} else if (type.equals("ClockModel")) {
        		partition = ((GenericTreeLikelihood) partition).branchRateModelInput.get();
        	} else if (type.equals("Tree")) {
        		partition = (BEASTInterface) ((GenericTreeLikelihood) partition).treeInput.get();
        	}
            String partitionID = partition.getID();
            partitionID = partitionID.substring(partitionID.lastIndexOf('.') + 1);
            if (partitionID.length() > 1 && partitionID.charAt(1) == ':') {
            	partitionID = partitionID.substring(2);
            }
            if (partitionID.equals(sourceID)) {
            	source = i;
            }
            if (partitionID.equals(targetID)) {
            	target = i;
            }
        } 
    	if (target == -1) {
    		throw new RuntimeException("Programmer error: sourceID and targetID should be in list");
    	}
    	
		CompoundDistribution likelihoods = (CompoundDistribution) doc.pluginmap.get("likelihood");
		
		GenericTreeLikelihood likelihoodSource = (GenericTreeLikelihood) likelihoods.pDistributions.get().get(source);
		GenericTreeLikelihood likelihood = (GenericTreeLikelihood) likelihoods.pDistributions.get().get(target);
		PartitionContext oldContext = doc.getContextFor(likelihoodSource);
		PartitionContext newContext = doc.getContextFor(likelihood);
		// this ensures the config.sync does not set any input value
		config._input.setValue(null, config);

    	if (type.equals("SiteModel")) {		
			SiteModelInterface siteModelSource = likelihoodSource.siteModelInput.get();
			SiteModelInterface  siteModel = null;
			try {
				siteModel = (SiteModel.Base) BeautiDoc.deepCopyPlugin((BEASTInterface) siteModelSource,
					likelihood, (MCMC) doc.mcmc.get(), oldContext, newContext, doc, null);
			} catch (RuntimeException e) {
				Alert.showMessageDialog(this, "Could not clone " + sourceID + " to " + targetID + " " + e.getMessage());
				return;
			}
			likelihood.siteModelInput.setValue(siteModel, likelihood);
			return;
    	} else if (type.equals("ClockModel")) {
    		BranchRateModel clockModelSource = likelihoodSource.branchRateModelInput.get();
    		BranchRateModel clockModel = null;
			try {
				clockModel = (BranchRateModel) BeautiDoc.deepCopyPlugin((BEASTInterface) clockModelSource,
						likelihood, (MCMC) doc.mcmc.get(), oldContext, newContext, doc, null);
			} catch (Exception e) {
				Alert.showMessageDialog(this, "Could not clone " + sourceID + " to " + targetID + " " + e.getMessage());
				return;
			}
			// make sure that *if* the clock model has a tree as input, it is
			// the same as for the likelihood
			TreeInterface tree = null;
			try {
				for (Input<?> input : ((BEASTInterface) clockModel).listInputs()) {
					if (input.getName().equals("tree")) {
						tree = (TreeInterface) input.get();
					}

				}
			} catch (IllegalArgumentException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			if (tree != null && tree != likelihood.treeInput.get()) {
				//likelihood.treeInput.setValue(tree, likelihood);
				Alert.showMessageDialog(null, "Cannot clone clock model with different trees");
				return;
			}

			likelihood.branchRateModelInput.setValue(clockModel, likelihood);
			return;
    	} else if (type.equals("Tree")) {
			TreeInterface tree = null;
			TreeInterface treeSource = likelihoodSource.treeInput.get();
			try {
			tree = (TreeInterface) BeautiDoc.deepCopyPlugin((BEASTInterface) treeSource, likelihood,
							(MCMC) doc.mcmc.get(), oldContext, newContext, doc, null);
				} catch (Exception e) {
					Alert.showMessageDialog(this, "Could not clone " + sourceID + " to " + targetID + " " + e.getMessage());
					return;
			}
			// sanity check: make sure taxon sets are compatible
            Taxon.assertSameTaxa(tree.getID(), tree.getTaxonset().getTaxaNames(),
                    likelihood.dataInput.get().getID(), likelihood.dataInput.get().getTaxaNames());

			likelihood.treeInput.setValue(tree, likelihood);
			return;

    	} else {
    		throw new RuntimeException("Programmer error calling cloneFrom: Should only clone Site/Clock/Tree model");
    	}
    } // cloneFrom

    private boolean isToClone() {
        return listOfPartitions != null && listOfPartitions.getSelectionModel().getSelectedIndices().size() > 1;
    }

//    public static boolean soundIsPlaying = false;
//
//    public static synchronized void playSound(final String url) {
//        new Thread(new Runnable() {
//            public void run() {
//                try {
//                    synchronized (this) {
//                        if (soundIsPlaying) {
//                            return;
//                        }
//                        soundIsPlaying = true;
//                    }
//                    Clip clip = AudioSystem.getClip();
//                    AudioInputStream inputStream = AudioSystem.getAudioInputStream(getClass().getResourceAsStream("/beastfx.app.beauti/" + url));
//                    clip.open(inputStream);
//                    clip.start();
//                    Thread.sleep(500);
//                    synchronized (this) {
//                        soundIsPlaying = false;
//                    }
//                } catch (Exception e) {
//                    soundIsPlaying = false;
//                    System.err.println(e.getMessage());
//                }
//            }
//        }).start();
//    }

    @Override
    public void valueChanged(ListSelectionEvent e) {
        //System.err.print("BeautiPanel::valueChanged " + m_iPartition + " => ");
        if (e != null) {
            config.sync(partitionIndex);
            if (listOfPartitions != null) {
                partitionIndex = Math.max(0, listOfPartitions.getSelectionModel().getSelectedIndex());
            }
        }
//        BeautiPanel.playSound("woosh.wav");
        //System.err.println(m_iPartition);
        try {
            refreshPanel();

            centralComponent.repaint();
            repaint();

            // hack to ensure m_centralComponent is repainted RRB: is there a better way???
            if (Frame.getFrames().length == 0) {
                // happens at startup
                return;
            }
            Frame frame = Frame.getFrames()[Frame.getFrames().length - 1];
            frame.setSize(frame.getSize());
            //Frame frame = frames[frames.length - 1];
//			Dimension size = frames[frames.length-1].getSize();
//			frames[frames.length-1].setSize(size);

//			m_centralComponent.repaint();
//			m_centralComponent.requestFocus();
            centralComponent.requestFocus();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
} // class BeautiPanel
