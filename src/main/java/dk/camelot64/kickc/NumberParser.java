package dk.camelot64.kickc;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantInteger;

/** Parser for converting literal numbers to the corresponding Java Integer/Double */
public class NumberParser {

   public static ConstantInteger parseIntegerLiteral(String literal) {

      literal = literal.toLowerCase().replace("_", "").replace("'", "");
      if(literal.contains(".")) {
         if(literal.endsWith("f")) {
            literal = literal.substring(0, literal.length() - 1);
         }
         double d;
         if(literal.startsWith("$")) {
            d = Double.parseDouble("0x" + literal.substring(1) + "p0");
         } else if(literal.startsWith("0x")) {
            d = Double.parseDouble(literal + "p0");
         } else {
            d = Double.parseDouble(literal);
         }
         long fixedVal = Math.round(d * 256.0);
         return new ConstantInteger(fixedVal, SymbolType.NUMBER);
      }
      SymbolType type = SymbolType.NUMBER;
      if(literal.endsWith("ub") || literal.endsWith("uc")) {
         type = SymbolType.BYTE;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("sb") || literal.endsWith("sc")) {
         type = SymbolType.SBYTE;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("uw") || literal.endsWith("ui")|| literal.endsWith("us")) {
         type = SymbolType.WORD;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("sw") || literal.endsWith("si")|| literal.endsWith("ss")) {
         type = SymbolType.SWORD;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("ud") || literal.endsWith("ul")) {
         type = SymbolType.DWORD;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("sd") || literal.endsWith("sl")) {
         type = SymbolType.SDWORD;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("l")) {
         type = SymbolType.SDWORD;
         literal = literal.substring(0, literal.length()-1);
      }  else if(literal.endsWith("u")) {
         type = SymbolType.WORD;
         literal = literal.substring(0, literal.length()-1);
      }

         Long value;
         if(literal.startsWith("0x")) {
            value = Long.parseLong(literal.substring(2), 16);
         } else if(literal.startsWith("$")) {
            value = Long.parseLong(literal.substring(1), 16);
         } else if(literal.startsWith("0b")) {
            value = Long.parseLong(literal.substring(2), 2);
         } else if(literal.startsWith("%")) {
            value = Long.parseLong(literal.substring(1), 2);
         } else {
            value = Long.parseLong(literal);
         }
         return new ConstantInteger(value, type);

   }

   public static Number parseLiteral(String literal) {
      ConstantInteger constantInteger = parseIntegerLiteral(literal);
      return constantInteger.getValue();
   }


}
