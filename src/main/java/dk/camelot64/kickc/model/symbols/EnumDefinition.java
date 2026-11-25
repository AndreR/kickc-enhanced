package dk.camelot64.kickc.model.symbols;


import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.types.SymbolTypeEnum;

public class EnumDefinition extends Scope {

   private SymbolType underlyingType = SymbolType.BYTE;

   public EnumDefinition(String name, Scope parentScope) {
      super(name, parentScope, parentScope.getSegmentData());
   }

   public SymbolType getUnderlyingType() {
      return underlyingType;
   }

   public void setUnderlyingType(SymbolType underlyingType) {
      this.underlyingType = underlyingType;
   }

   @Override
   public SymbolType getType() {
      return new SymbolTypeEnum(this, false, false);
   }

   @Override
   public String toString(Program program) {
      return "enum-"+getFullName();
   }


}
