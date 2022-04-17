package beastfx.app.inputeditor;


import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Frame;
import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Scanner;

//import javax.swing.Icon;
//import javax.swing.ImageIcon;
//import javax.swing.JDialog;
//import beastfx.app.util.Alert;
//import javax.swing.border.EmptyBorder;

import beast.base.core.BEASTInterface;
import beast.base.core.Input;
import beast.base.inference.MCMC;
import beast.base.parser.XMLProducer;
import beast.pkgmgmt.BEASTClassLoader;
import beastfx.app.util.Alert;

/**
 * Dialog for editing BEASTObjects.
 * <p/>
 * This dynamically creates a dialog consisting of
 * InputEditors associated with the inputs of a BEASTObject.
 * *
 */

public class BEASTObjectDialog extends Dialog {
    private static final long serialVersionUID = 1L;
    /**
     * plug in to be edited *
     */

    private boolean m_bOK = false;

    public BEASTObjectPanel m_panel;

    BeautiDoc doc;
    
    public BEASTObjectDialog(BEASTObjectPanel panel, BeautiDoc doc) {
        init(panel);
        this.doc = doc;
    }

    public BEASTObjectDialog(BEASTInterface beastObject, Class<? extends BEASTInterface> aClass, List<BEASTInterface> beastObjects, BeautiDoc doc) {
        this(new BEASTObjectPanel(beastObject, aClass, beastObjects, doc), doc);
    }

    public BEASTObjectDialog(BEASTInterface beastObject, Class<?> type, BeautiDoc doc) {
        this(new BEASTObjectPanel(beastObject, type, doc), doc);
    }

    final public static String ICONPATH = "/beastfx/app/inputeditor/icons/";
    
    public boolean showDialog() {
        URL url = BEASTObjectDialog.class.getClassLoader().getResource(ICONPATH + "beast.png");
        Icon icon = new ImageIcon(url);
        Alert optionPane = new Alert(m_panel,
                Alert.QUESTION_MESSAGE,
                Alert.OK_CANCEL_OPTION,
                icon,
                null,
                null);
        optionPane.setBorder(new EmptyBorder(12, 12, 12, 12));

        Frame frame = (doc != null ? doc.getFrame(): Frame.getFrames()[0]);
        final Dialog dialog = optionPane.createDialog(frame, this.getTitle());
        dialog.pack();

        dialog.setVisible(true);

        int result = Alert.CANCEL_OPTION;
        Integer value = (Integer) optionPane.getValue();
        if (value != null && value != -1) {
            result = value;
        }
        m_bOK = (result != Alert.CANCEL_OPTION);
        return m_bOK;
    }
    
    /* to be called when OK is pressed **/
    public void accept(BEASTInterface beastObject, BeautiDoc doc) {
        try {
            for (Input<?> input : m_panel.m_beastObject.listInputs()) {
            	if (input.get() != null && (input.get() instanceof List)) {
                    // setInpuValue (below) on lists does not lead to expected result
            		// it appends values to the list instead, so we have to clear it first
                    List<?> list = (List<?>)beastObject.getInput(input.getName()).get();
                    list.clear();
            	}
            	beastObject.setInputValue(input.getName(), input.get());
            }
            beastObject.setID(m_panel.m_beastObject.getID());
            if (doc != null) {
            	doc.addPlugin(beastObject);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void init(BEASTObjectPanel panel) {
        this.m_panel = panel;

        setModal(true);

        add(BorderLayout.CENTER, panel);

        setTitle(panel.m_beastObject.getID() + " Editor");


//        /* add cancel and ok buttons at the bottom */
//        HBox cancelOkBox = new HBox();
//        cancelOkBox.setBorder(new EtchedBorder());
//        Button okButton = new Button("Ok");
//        okButton.setOnAction(new ActionListener() {
//
//            // implementation ActionListener
//            public void actionPerformed(ActionEvent e) {
//                m_bOK = true;
//                dispose();
//            }
//        });
//        Button cancelButton = new Button("Cancel");
//        cancelButton.setOnAction(new ActionListener() {
//
//            // implementation ActionListener
//            public void actionPerformed(ActionEvent e) {
//                m_bOK = false;
//                dispose();
//            }
//        });
//        cancelOkBox.add(new Separator());
//        cancelOkBox.add(okButton);
//        cancelOkBox.add(new Separator());
//        cancelOkBox.add(cancelButton);
//        cancelOkBox.add(new Separator());
//
//        add(BorderLayout.SOUTH, cancelOkBox);
//
//        Dimension dim = panel.getPreferredSize();
//        Dimension dim2 = cancelOkBox.getPreferredSize();
//        setSize(dim.width + 10, dim.height + dim2.height + 30);
    } // c'tor

    public boolean getOK(BeautiDoc doc) {
        //PluginDialog.m_position.x -= 30;
        //PluginDialog.m_position.y -= 30;
        if (m_bOK) {
            String oldID = m_panel.m_beastObject.getID();
            BEASTObjectPanel.g_plugins.remove(oldID);
            m_panel.m_beastObject.setID(m_panel.m_identry.getText());
            BEASTObjectPanel.registerPlugin(m_panel.m_beastObject.getID(), m_panel.m_beastObject, doc);
        }
        return m_bOK;
    }

    /**
     * rudimentary test *
     */
    public static void main(String[] args) {
        BEASTObjectDialog dlg = null;
        try {
            if (args.length == 0) {
                dlg = new BEASTObjectDialog(new BEASTObjectPanel(new MCMC(), Runnable.class, null), null);
            } else if (args[0].equals("-x")) {
                StringBuilder text = new StringBuilder();
                String NL = System.getProperty("line.separator");
                Scanner scanner = new Scanner(new File(args[1]));
                try {
                    while (scanner.hasNextLine()) {
                        text.append(scanner.nextLine() + NL);
                    }
                } finally {
                    scanner.close();
                }
                BEASTInterface beastObject = new beast.base.parser.XMLParser().parseBareFragment(text.toString(), false);
                dlg = new BEASTObjectDialog(new BEASTObjectPanel(beastObject, beastObject.getClass(), null), null);
            } else if (args.length == 1) {
                dlg = new BEASTObjectDialog(new BEASTObjectPanel((BEASTInterface) BEASTClassLoader.forName(args[0]).newInstance(), BEASTClassLoader.forName(args[0]), null), null);
            } else if (args.length == 2) {
                dlg = new BEASTObjectDialog(new BEASTObjectPanel((BEASTInterface) BEASTClassLoader.forName(args[0]).newInstance(), BEASTClassLoader.forName(args[1]), null), null);
            } else {
                throw new IllegalArgumentException("Incorrect number of arguments");
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Usage: " + BEASTObjectDialog.class.getName() + " [-x file ] [class [type]]\n" +
                    "where [class] (optional, default MCMC) is a BEASTObject to edit\n" +
                    "and [type] (optional only if class is specified, default Runnable) the type of the BEASTObject.\n" +
                    "for example\n" +
                    "");
            System.exit(1);
        }
        dlg.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        if (dlg.showDialog()) {
            BEASTInterface beastObject = dlg.m_panel.m_beastObject;
            String xml = new XMLProducer().modelToXML(beastObject);
            System.out.println(xml);
        }
    } // main
} // class PluginDialog

