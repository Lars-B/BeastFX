package beastfx.app.beauti;



//http://www.java2s.com/Code/Java/Swing-Components/JComboBoxaddingautomaticcompletionHandlingtheinitialselection.htm
//Code from: http://www.orbital-computer.de/ComboBox/
/*
Inside ComboBox: adding automatic completion

Author: Thomas Bierhance
        thomas@orbital-computer.de
*/

/*
Handling the initial selection

It is a quiet annoying that the initially selected item is not shown in the combo box. This
can be easily changed in the constructor of the auto completing document.
*/
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;

import javax.swing.JFrame;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;
import javax.swing.text.PlainDocument;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.EventHandler;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;


public class S11InitialSelection extends PlainDocument {
	
	// https://stackoverflow.com/questions/19924852/autocomplete-combobox-in-javafx
	public class FxUtilTest {

	    public interface AutoCompleteComparator<T> {
	        boolean matches(String typedText, T objectToCompare);
	    }

	    public static<T> void autoCompleteComboBoxPlus(ComboBox<T> comboBox, AutoCompleteComparator<T> comparatorMethod) {
	        ObservableList<T> data = comboBox.getItems();

	        comboBox.setEditable(true);
	        comboBox.getEditor().focusedProperty().addListener(observable -> {
	            if (comboBox.getSelectionModel().getSelectedIndex() < 0) {
	                comboBox.getEditor().setText(null);
	            }
	        });
	        comboBox.addEventHandler(KeyEvent.KEY_PRESSED, t -> comboBox.hide());
	        comboBox.addEventHandler(KeyEvent.KEY_RELEASED, new EventHandler<KeyEvent>() {

	            private boolean moveCaretToPos = false;
	            private int caretPos;

	            @Override
	            public void handle(KeyEvent event) {
	                if (event.getCode() == KeyCode.UP) {
	                    caretPos = -1;
	                    if (comboBox.getEditor().getText() != null) {
	                        moveCaret(comboBox.getEditor().getText().length());
	                    }
	                    return;
	                } else if (event.getCode() == KeyCode.DOWN) {
	                    if (!comboBox.isShowing()) {
	                        comboBox.show();
	                    }
	                    caretPos = -1;
	                    if (comboBox.getEditor().getText() != null) {
	                        moveCaret(comboBox.getEditor().getText().length());
	                    }
	                    return;
	                } else if (event.getCode() == KeyCode.BACK_SPACE) {
	                    if (comboBox.getEditor().getText() != null) {
	                        moveCaretToPos = true;
	                        caretPos = comboBox.getEditor().getCaretPosition();
	                    }
	                } else if (event.getCode() == KeyCode.DELETE) {
	                    if (comboBox.getEditor().getText() != null) {
	                        moveCaretToPos = true;
	                        caretPos = comboBox.getEditor().getCaretPosition();
	                    }
	                } else if (event.getCode() == KeyCode.ENTER) {
	                    return;
	                }

	                if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.LEFT || event.getCode().equals(KeyCode.SHIFT) || event.getCode().equals(KeyCode.CONTROL)
	                        || event.isControlDown() || event.getCode() == KeyCode.HOME
	                        || event.getCode() == KeyCode.END || event.getCode() == KeyCode.TAB) {
	                    return;
	                }

	                ObservableList<T> list = FXCollections.observableArrayList();
	                for (T aData : data) {
	                    if (aData != null && comboBox.getEditor().getText() != null && comparatorMethod.matches(comboBox.getEditor().getText(), aData)) {
	                        list.add(aData);
	                    }
	                }
	                String t = "";
	                if (comboBox.getEditor().getText() != null) {
	                    t = comboBox.getEditor().getText();
	                }

	                comboBox.setItems(list);
	                comboBox.getEditor().setText(t);
	                if (!moveCaretToPos) {
	                    caretPos = -1;
	                }
	                moveCaret(t.length());
	                if (!list.isEmpty()) {
	                    comboBox.show();
	                }
	            }

	            private void moveCaret(int textLength) {
	                if (caretPos == -1) {
	                    comboBox.getEditor().positionCaret(textLength);
	                } else {
	                    comboBox.getEditor().positionCaret(caretPos);
	                }
	                moveCaretToPos = false;
	            }
	        });
	    }

	    public static<T> T getComboBoxValue(ComboBox<T> comboBox){
	        if (comboBox.getSelectionModel().getSelectedIndex() < 0) {
	            return null;
	        } else {
	            return comboBox.getItems().get(comboBox.getSelectionModel().getSelectedIndex());
	        }
	    }

	}	
	
	FxUtilTest.autoCompleteComboBoxPlus(myComboBox, (typedText, itemToCompare) -> itemToCompare.getName().toLowerCase().contains(typedText.toLowerCase()) || itemToCompare.getAge().toString().equals(typedText));

	myComboBox.setConverter(new StringConverter<>() {

	    @Override
	    public String toString(YourObject object) {
	        return object != null ? object.getName() : "";
	    }

	    @Override
	    public YourObject fromString(String string) {
	        return myComboBox.getItems().stream().filter(object ->
	                object.getName().equals(string)).findFirst().orElse(null);
	    }

	});

	ComboBox<Object> comboBox;
    // ComboBoxModel<Object> model;
    TextField editor;
    
    // flag to indicate if setSelectedItem has been called
    // subsequent calls to remove/insertString should be ignored
    boolean selecting=false;

    public S11InitialSelection(final ComboBox<Object> comboBox) {
        this.comboBox = comboBox;
        comboBox.setEditable(true);
        // model = comboBox.getModel();
        editor = (JTextComponent) comboBox.getEditor().getEditorComponent();
        editor.setDocument(this);
        comboBox.setOnAction(e -> {
                if (!selecting) highlightCompletedText(0);
            });
        editor.setOnKeyReleased(e-> {
                if (comboBox.isDisplayable()) comboBox.setPopupVisible(true);
        });
        // Handle initially selected object
        Object selected = comboBox.getValue();
        if (selected!=null) setText(selected.toString());
        highlightCompletedText(0);
    }

    @Override
	public void remove(int offs, int len) throws BadLocationException {
        // return immediately when selecting an item
        if (selecting) return;
        super.remove(offs, len);
    }

    @Override
	public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
        // return immediately when selecting an item
        if (selecting) return;
        // insert the string into the document
        super.insertString(offs, str, a);
        // lookup and select a matching item
        Object item = lookupItem(getText(0, getLength()));
        if (item != null) {
            setSelectedItem(item);
        } else {
            // keep old item selected if there is no match
            item = comboBox.getValue();
            // imitate no insert (later on offs will be incremented by str.length(): selection won't move forward)
            offs = offs-str.length();
            // provide feedback to the user that his input has been received but can not be accepted
            comboBox.getToolkit().beep(); // when available use: UIManager.getLookAndFeel().provideErrorFeedback(comboBox);
        }
        setText(item.toString());
        // select the completed part
        highlightCompletedText(offs+str.length());
    }

    private void setText(String text) {
        try {
            // remove all text and insert the completed string
            super.remove(0, getLength());
            super.insertString(0, text, null);
        } catch (BadLocationException e) {
            throw new RuntimeException(e.toString());
        }
    }

    private void highlightCompletedText(int start) {
        editor.setCaretPosition(getLength());
        editor.moveCaretPosition(start);
    }

    private void setSelectedItem(Object item) {
        selecting = true;
        model.setSelectedItem(item);
        selecting = false;
    }

    private Object lookupItem(String pattern) {
        Object selectedItem = model.getSelectedItem();
        // only search for a different item if the currently selected does not match
        if (selectedItem != null && startsWithIgnoreCase(selectedItem.toString(), pattern)) {
            return selectedItem;
        } else {
            // iterate over all items
            for (int i=0, n=model.getSize(); i < n; i++) {
                Object currentItem = model.getElementAt(i);
                // current item starts with the pattern?
                if (startsWithIgnoreCase(currentItem.toString(), pattern)) {
                    return currentItem;
                }
            }
        }
        // no item starts with the pattern => return null
        return null;
    }

    // checks if str1 starts with str2 - ignores case
    private boolean startsWithIgnoreCase(String str1, String str2) {
        return str1.toUpperCase().startsWith(str2.toUpperCase());
    }

    private static void createAndShowGUI() {
        // the combo box (add/modify items if you like to)
        ComboBox<Object> comboBox = new ComboBox<>();
        comboBox.getItems().addAll(new Object[] {"Ester", "Jordi", "Jordina", "Jorge", "Sergi"});
        // has to be editable
        comboBox.setEditable(true);
        // change the editor's document
        new S11InitialSelection(comboBox);

        // create and show a window containing the combo box
        JFrame frame = new JFrame();
        frame.setDefaultCloseOperation(3);
        frame.getContentPane().add(comboBox);
        frame.pack(); frame.setVisible(true);
    }


    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
			public void run() {
                createAndShowGUI();
            }
        });
    }
}
