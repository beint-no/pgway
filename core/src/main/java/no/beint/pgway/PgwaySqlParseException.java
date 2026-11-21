package no.beint.pgway;

/** Thrown when a migration SQL script has invalid directives, malformed lexical syntax, or forbidden statements. */
public final class PgwaySqlParseException extends PgwayException {
    private static final long serialVersionUID = 1L;
    private final String script;
    private final int line;

    public PgwaySqlParseException(String script, int line, String message) {
        super(script + ":" + line + ": " + message);
        this.script = script;
        this.line = line;
    }

    public String script() {
        return script;
    }

    public int line() {
        return line;
    }
}
