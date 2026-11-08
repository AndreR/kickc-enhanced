package dk.camelot64.kickc;

/**
 * Console progress reporting for KickC compilation phases.
 * Provides in-place live status updates on interactive terminals
 * and clean formatted phase summaries for logs and non-interactive output.
 */
public class CompilerProgress {

   private static final boolean IS_CONSOLE = (System.console() != null)
         || "true".equalsIgnoreCase(System.getProperty("kickc.progress"))
         || "true".equalsIgnoreCase(System.getenv("KICKC_PROGRESS"));

   private static String currentStatus = "";

   /**
    * Update current in-flight progress on the current line (interactive console only).
    *
    * @param message The progress message
    */
   public static void status(String message) {
      if(IS_CONSOLE) {
         int pad = Math.max(0, currentStatus.length() - message.length());
         String padding = (pad > 0) ? " ".repeat(pad) : "";
         currentStatus = message;
         System.out.print("\r" + message + padding);
         System.out.flush();
      }
   }

   /**
    * Clear any in-flight status message on the current line.
    */
   public static void clearStatus() {
      if(IS_CONSOLE && !currentStatus.isEmpty()) {
         System.out.print("\r" + " ".repeat(currentStatus.length()) + "\r");
         System.out.flush();
         currentStatus = "";
      }
   }

   /**
    * Complete a phase, printing the final timing line and padding out any leftover characters.
    *
    * @param phaseName The phase label
    * @param seconds Elapsed seconds
    * @param details Optional details (e.g. rounds, count)
    */
   public static void done(String phaseName, double seconds, String details) {
      String line = String.format("%-38s %6.2f s", phaseName, seconds);
      if(details != null && !details.isEmpty()) {
         line += "  " + details;
      }
      if(IS_CONSOLE) {
         int pad = Math.max(0, currentStatus.length() - line.length());
         String padding = (pad > 0) ? " ".repeat(pad) : "";
         System.out.println("\r" + line + padding);
         System.out.flush();
         currentStatus = "";
      } else {
         System.out.println(line);
      }
   }
}
