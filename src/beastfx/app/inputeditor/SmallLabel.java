package beastfx.app.inputeditor;



import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Background;
import javafx.scene.paint.Paint;

/**
 * Miniature round label
 */
public class SmallLabel extends Label {
    public String circleColor = "blue";

    public SmallLabel(String label, String circleColor) {
        super(label);
        setText("");
        String style = 
                "-fx-background-radius: 20em; " +
                "-fx-min-width: 15px; " +
                "-fx-min-height: 15px; " +
                "-fx-max-width: 15px; " +
                "-fx-max-height: 15px; " +
                "-fx-background-color: " + circleColor;
        setStyle(style);

        this.circleColor = circleColor;
        super.setVisible(true);
    } // c'tor

    public void setColor(String circleColor) {
        Background.fill(Paint.valueOf(circleColor));
    }

    public void setTooltip(String text) {
	    final Tooltip tooltip = new Tooltip();
	    tooltip.setText(text);
    	setTooltip(tooltip);
    }

} // class SmallButton
