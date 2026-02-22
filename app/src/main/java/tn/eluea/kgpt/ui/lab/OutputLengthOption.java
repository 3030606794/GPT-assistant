package tn.eluea.kgpt.ui.lab;

/**
 * Output length option (max tokens).
 */
public class OutputLengthOption {
    public final int tokens;
    public final String title;
    public final String subtitle;
    public final boolean isCustom;

    public OutputLengthOption(int tokens, String title, String subtitle, boolean isCustom) {
        this.tokens = tokens;
        this.title = title;
        this.subtitle = subtitle;
        this.isCustom = isCustom;
    }
}
