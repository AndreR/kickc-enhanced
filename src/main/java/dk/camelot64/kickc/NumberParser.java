package dk.camelot64.kickc;

import dk.camelot64.kickc.model.CompileError;
import dk.camelot64.kickc.model.types.SymbolType;
import dk.camelot64.kickc.model.values.ConstantInteger;

/** Parser for converting literal numbers to the corresponding Java Integer/Double */
public class NumberParser {

   public static ConstantInteger parseIntegerLiteral(String literal) {

      literal = literal.toLowerCase().replace("_", "").replace("'", "");
      boolean isHex = literal.startsWith("0x") || literal.startsWith("$");
      double scale = -1.0;
      String fixedPointSuffix = null;
      if(!isHex || literal.contains(".")) {
         if(literal.endsWith("f24")) {
            scale = 16777216.0;
            fixedPointSuffix = "f24";
            literal = literal.substring(0, literal.length() - 3);
         } else if(literal.endsWith("f16")) {
            scale = 65536.0;
            fixedPointSuffix = "f16";
            literal = literal.substring(0, literal.length() - 3);
         } else if(literal.endsWith("f8")) {
            scale = 256.0;
            fixedPointSuffix = "f8";
            literal = literal.substring(0, literal.length() - 2);
         } else if(literal.endsWith("f") && literal.contains(".")) {
            scale = 256.0;
            fixedPointSuffix = "f8";
            literal = literal.substring(0, literal.length() - 1);
         } else if(literal.contains(".")) {
            scale = 256.0;
         }
      }

      if(scale > 0.0) {
         double d;
         if(literal.startsWith("$")) {
            d = Double.parseDouble("0x" + literal.substring(1) + "p0");
         } else if(literal.startsWith("0x")) {
            d = Double.parseDouble(literal + "p0");
         } else {
            d = Double.parseDouble(literal);
         }
         long fixedVal = Math.round(d * scale);
         ConstantInteger ci = new ConstantInteger(fixedVal, SymbolType.NUMBER);
         ci.setFixedPointSuffix(fixedPointSuffix);
         return ci;
      }
      SymbolType type = SymbolType.NUMBER;
      if(literal.endsWith("ub") || literal.endsWith("uc")) {
         type = SymbolType.BYTE;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("sb") || literal.endsWith("sc")) {
         type = SymbolType.SBYTE;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("ut") || literal.endsWith("u24")) {
         type = SymbolType.TRIBYTE;
         literal = literal.substring(0, literal.length()-2);
      }  else if(literal.endsWith("st") || literal.endsWith("s24")) {
         type = SymbolType.STRIBYTE;
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
