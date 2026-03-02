package tn.eluea.kgpt.ui.lab;

/**
 * Output length option (max tokens).
 */
public class OutputLengthOption {
    public final int tokens;
    public final String title;
    public final String subtitle;
    public final boolean isCustom;

    /** UI state: whether this row is selectable under current cached model cap. */
    public boolean enabled = true;

    /** Optional 4th line note shown for the floor preset that matches current model cap. */
    public String modelCapNote;

    public OutputLengthOption(int tokens, String title, String subtitle, boolean isCustom) {
        this.tokens = tokens;
        this.title = title;
        this.subtitle = subtitle;
        this.isCustom = isCustom;
    }
}
