package beastfx.app.beauti;

import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import javax.swing.JPanel;


public class ClonePartitionPanel extends VBox {

    final BeautiPanel beautiPanel;
    final ComboBox<Object> cloneFromComboBox;
    final Button okButton = new Button("OK");

	public ClonePartitionPanel(BeautiPanel beautiPanel) {
        this.beautiPanel = beautiPanel;

        DefaultListModel<String> listModel = beautiPanel.listModel;
        Object[] models = new Object[listModel.getSize()];
        for(int i=0; i < listModel.getSize(); i++){
            models[i] = listModel.getElementAt(i);
        }

        cloneFromComboBox = new ComboBox<>(models);
        // has to be editable
        cloneFromComboBox.setEditable(true);
        // change the editor's document
        new S11InitialSelection(cloneFromComboBox);

        init();
    }


    public void init() {
        // setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        FlowPane jPanel = new FlowPane();

        Label label = new Label("Clone from");
        jPanel.getChildren().add(label);

        cloneFromComboBox.setMaximumRowCount(10);
        jPanel.getChildren().add(cloneFromComboBox);

        pane.getChildren().add(Box.createRigidArea(new Dimension(0, 10)));
        pane.getChildren().add(jPanel);
        pane.getChildren().add(Box.createVerticalGlue());
        pane.getChildren().add(Box.createVerticalStrut(5));

        okButton.setId("ok");
        okButton.setTooltip(new Tooltip("Click to clone configuration from the above selected partition " +
                "into all selected partitions on the left."));
        okButton.setOnAction(e -> {
                clonePartitions();
            });
        pane.getChildren().add(okButton);

    } // init

    protected void clonePartitions() {
        String sourceId = cloneFromComboBox.getValue().toString();

        for (Object targetId : beautiPanel.listOfPartitions.getSelectedValuesList()) {
             beautiPanel.cloneFrom(sourceId, targetId.toString());
        }
    }
}
