package dk.camelot64.kickc.asm;

/**
 * Zero-waste loop alignment directive for KickAssembler.
 * Emits .if ((* & $ff) > (256 - loopLength)) .align $100
 * Only advances PC to the next 256-byte page boundary if the loop would otherwise cross the boundary.
 */
public class AsmLoopAlign extends AsmLine {

   private final int loopLength;

   public AsmLoopAlign(int loopLength) {
      this.loopLength = loopLength;
   }

   public int getLoopLength() {
      return loopLength;
   }

   @Override
   public int getLineBytes() {
      return 0;
   }

   @Override
   public double getLineCycles() {
      return 0;
   }

   @Override
   public String getAsm() {
      return ".if ((* & $ff) > " + (256 - loopLength) + ") .align $100";
   }

   @Override
   public String toString() {
      return getAsm();
   }

}
