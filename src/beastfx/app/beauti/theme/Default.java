package beastfx.app.beauti.theme;

import beastfx.app.beauti.ThemeProvider;
import javafx.scene.Scene;

public class Default extends ThemeProvider {
	public Default() {}
	public String getThemeName() {return "Default";}
	public boolean loadMyStyleSheet(Scene scene) {return ThemeProvider.loadStyleSheet(scene, "/BeastFX/themes/default.css");}
}