package dk.camelot64.kickc.model;

/**
 * Metadata describing the type and banking behavior of a code or data segment.
 */
public class SegmentTypeInfo {

   public enum Kind {
      RAM_RESIDENT,
      RAM_OVERLAY,
      ROM_BANK,
      ROM
   }

   private final Kind kind;
   private final int bankId;

   public SegmentTypeInfo(Kind kind) {
      this(kind, -1);
   }

   public SegmentTypeInfo(Kind kind, int bankId) {
      this.kind = kind;
      this.bankId = bankId;
   }

   public Kind getKind() {
      return kind;
   }

   public int getBankId() {
      return bankId;
   }

   public boolean isRom() {
      return kind == Kind.ROM || kind == Kind.ROM_BANK;
   }

   public boolean isOverlay() {
      return kind == Kind.RAM_OVERLAY;
   }

   public boolean isResident() {
      return kind == Kind.RAM_RESIDENT;
   }

   @Override
   public String toString() {
      if(bankId >= 0) {
         return kind.name() + "(" + bankId + ")";
      }
      return kind.name();
   }
}
