package dk.camelot64.kickc.asm;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.cpufamily6502.CpuOpcode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Perform static analysis of an ASM program infering information about the values of registers entering each instruction
 */
public class AsmProgramStaticRegisterValues {

   private AsmProgram program;

   private Map<AsmInstruction, AsmRegisterValues> values;

   public AsmProgramStaticRegisterValues(AsmProgram program) {
      this.program = program;
      this.values = new LinkedHashMap<>();
      initValues();
   }

   public static Integer getImmValue(String parameter) {
      if(parameter == null) return null;
      try {
         String p = parameter.trim();
         if(p.startsWith("#")) p = p.substring(1).trim();
         if(p.isEmpty()) return null;
         Long val = evalImmExpr(p);
         if(val != null) {
            return (int)(val & 0xffffffffL);
         }
      } catch(Exception e) {
         // ignore
      }
      return null;
   }

   private static boolean isBalanced(String s) {
      int depth = 0;
      for(int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if(c == '(') depth++;
         else if(c == ')') {
            depth--;
            if(depth < 0) return false;
         }
      }
      return depth == 0;
   }

   private static int findLastOperator(String s, char op) {
      int depth = 0;
      for(int i = s.length() - 1; i >= 0; i--) {
         char c = s.charAt(i);
         if(c == ')') depth++;
         else if(c == '(') depth--;
         else if(depth == 0 && c == op) return i;
      }
      return -1;
   }

   private static int findLastShiftOperator(String s) {
      int depth = 0;
      for(int i = s.length() - 2; i >= 0; i--) {
         char c = s.charAt(i);
         char next = s.charAt(i + 1);
         if(c == ')') depth++;
         else if(c == '(') depth--;
         else if(depth == 0) {
            if((c == '>' && next == '>') || (c == '<' && next == '<')) {
               return i;
            }
         }
      }
      return -1;
   }

   private static int findLastAddSubOperator(String s) {
      int depth = 0;
      for(int i = s.length() - 1; i > 0; i--) {
         char c = s.charAt(i);
         if(c == ')') depth++;
         else if(c == '(') depth--;
         else if(depth == 0 && (c == '+' || c == '-')) {
            char prev = s.charAt(i - 1);
            if(prev != '+' && prev != '-' && prev != '*' && prev != '/' && prev != '<' && prev != '>' && prev != '&' && prev != '|' && prev != '^') {
               return i;
            }
         }
      }
      return -1;
   }

   private static int findLastMulDivOperator(String s) {
      int depth = 0;
      for(int i = s.length() - 1; i >= 0; i--) {
         char c = s.charAt(i);
         if(c == ')') depth++;
         else if(c == '(') depth--;
         else if(depth == 0 && (c == '*' || c == '/')) {
            return i;
         }
      }
      return -1;
   }

   private static Long evalImmExpr(String s) {
      s = s.trim();
      if(s.isEmpty()) return null;
      if(s.startsWith("(") && s.endsWith(")") && isBalanced(s.substring(1, s.length() - 1))) {
         return evalImmExpr(s.substring(1, s.length() - 1));
      }
      if(s.startsWith("<")) {
         Long sub = evalImmExpr(s.substring(1));
         return sub != null ? (sub & 0xffL) : null;
      }
      if(s.startsWith(">")) {
         Long sub = evalImmExpr(s.substring(1));
         return sub != null ? ((sub >> 8) & 0xffL) : null;
      }
      if(s.startsWith("~")) {
         Long sub = evalImmExpr(s.substring(1));
         return sub != null ? (~sub & 0xffffffffL) : null;
      }
      int opIdx = findLastOperator(s, '|');
      if(opIdx > 0) {
         Long left = evalImmExpr(s.substring(0, opIdx));
         Long right = evalImmExpr(s.substring(opIdx + 1));
         return (left != null && right != null) ? (left | right) : null;
      }
      opIdx = findLastOperator(s, '^');
      if(opIdx > 0) {
         Long left = evalImmExpr(s.substring(0, opIdx));
         Long right = evalImmExpr(s.substring(opIdx + 1));
         return (left != null && right != null) ? (left ^ right) : null;
      }
      opIdx = findLastOperator(s, '&');
      if(opIdx > 0) {
         Long left = evalImmExpr(s.substring(0, opIdx));
         Long right = evalImmExpr(s.substring(opIdx + 1));
         return (left != null && right != null) ? (left & right) : null;
      }
      int shiftIdx = findLastShiftOperator(s);
      if(shiftIdx > 0) {
         boolean isRight = s.charAt(shiftIdx) == '>';
         Long left = evalImmExpr(s.substring(0, shiftIdx));
         Long right = evalImmExpr(s.substring(shiftIdx + 2));
         if(left != null && right != null) {
            return isRight ? (left >> right) : (left << right);
         }
         return null;
      }
      opIdx = findLastAddSubOperator(s);
      if(opIdx > 0) {
         char op = s.charAt(opIdx);
         Long left = evalImmExpr(s.substring(0, opIdx));
         Long right = evalImmExpr(s.substring(opIdx + 1));
         if(left != null && right != null) {
            return op == '+' ? (left + right) : (left - right);
         }
         return null;
      }
      opIdx = findLastMulDivOperator(s);
      if(opIdx > 0) {
         char op = s.charAt(opIdx);
         Long left = evalImmExpr(s.substring(0, opIdx));
         Long right = evalImmExpr(s.substring(opIdx + 1));
         if(left != null && right != null && right != 0) {
            return op == '*' ? (left * right) : (left / right);
         }
         return null;
      }
      return parsePrimaryToken(s);
   }

   private static Long parsePrimaryToken(String s) {
      s = s.trim();
      if(s.isEmpty()) return null;
      if(s.startsWith("+")) s = s.substring(1).trim();
      boolean neg = false;
      if(s.startsWith("-")) {
         neg = true;
         s = s.substring(1).trim();
      }
      Long val = null;
      try {
         if(s.startsWith("$")) {
            val = Long.parseLong(s.substring(1), 16);
         } else if(s.startsWith("0x") || s.startsWith("0X")) {
            val = Long.parseLong(s.substring(2), 16);
         } else if(s.startsWith("%")) {
            val = Long.parseLong(s.substring(1), 2);
         } else if(s.startsWith("'") && s.endsWith("'") && s.length() == 3) {
            val = (long) s.charAt(1);
         } else {
            val = Long.parseLong(s);
         }
      } catch(NumberFormatException e) {
         return null;
      }
      if(val != null && neg) {
         val = -val;
      }
      return val;
   }

   public static boolean matchImm(String immVal1, String immVal2) {
      if(immVal1 != null && immVal2 != null && immVal1.equals(immVal2)) {
         return true;
      }
      Integer immInt1 = getImmValue(immVal1);
      Integer immInt2 = getImmValue(immVal2);
      if(immInt1 != null && immInt2 != null && immInt1.equals(immInt2)) {
         return true;
      }
      return false;
   }

   public AsmRegisterValues getValues(AsmInstruction instruction) {
      return values.get(instruction);
   }

   private void initValues() {
      AsmRegisterValues current = new AsmRegisterValues();
      for(AsmChunk chunk : program.getChunks()) {
         for(AsmLine line : chunk.getLines()) {
            current = updateStaticRegisterValues(current, line);
         }
      }
   }

   private AsmRegisterValues updateStaticRegisterValues(AsmRegisterValues current, AsmLine line) {
      if(line instanceof AsmLabel) {
         current = new AsmRegisterValues();
      } else if(line instanceof AsmScopeBegin) {
         current = new AsmRegisterValues();
      } else if(line instanceof AsmScopeEnd) {
         current = new AsmRegisterValues();
      } else if(line instanceof AsmInlineKickAsm) {
         current = new AsmRegisterValues(current);
         final CpuClobber cpuClobber = ((AsmInlineKickAsm) line).getClobber();
         handleClobber(current, cpuClobber);
      } else if(line instanceof AsmInstruction) {
         AsmInstruction instruction = (AsmInstruction) line;
         values.put(instruction, current);
         current = new AsmRegisterValues(current);
         CpuOpcode cpuOpcode = instruction.getCpuOpcode();
         CpuClobber cpuClobber = cpuOpcode.getClobber();
         if(instruction.getCpuOpcode().getMnemonic().equals("jsr")) {
            cpuClobber = CpuClobber.CLOBBER_ALL;
         }
         handleClobber(current, cpuClobber);
         String mnemnonic = cpuOpcode.getMnemonic();
         CpuAddressingMode addressingMode = cpuOpcode.getAddressingMode();
         if((mnemnonic.equals("inc") || mnemnonic.equals("dec") || mnemnonic.equals("ror") || mnemnonic.equals("rol") || mnemnonic.equals("lsr") || mnemnonic.equals("asl")) && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            String modParam = instruction.getOperand1();
            if(current.getaMem() != null && current.getaMem().equals(modParam)) {
               current.setaMem(null);
            }
            if(current.getxMem() != null && current.getxMem().equals(modParam)) {
               current.setxMem(null);
            }
            if(current.getyMem() != null && current.getyMem().equals(modParam)) {
               current.setyMem(null);
            }
            if(current.getzMem() != null && current.getzMem().equals(modParam)) {
               current.setzMem(null);
            }
         }
         if(mnemnonic.equals("lda") && addressingMode.equals(CpuAddressingMode.IMM)) {
            current.setA(instruction.getOperand1());
            current.setaMem(null);
            Integer immValue = getImmValue(instruction.getOperand1());
            if(immValue != null) {
               current.setFlagZ(immValue == 0);
               current.setFlagN(immValue > 127);
            }
         }
         if(mnemnonic.equals("lda") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setaMem(instruction.getOperand1());
            current.setA(null);
         }
         if(mnemnonic.equals("sta") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setaMem(instruction.getOperand1());
            if(instruction.getOperand1().equals(current.getyMem())) current.setyMem(null);
            if(instruction.getOperand1().equals(current.getxMem())) current.setxMem(null);
            if(instruction.getOperand1().equals(current.getzMem())) current.setzMem(null);
         }
         if(mnemnonic.equals("ldx") && addressingMode.equals(CpuAddressingMode.IMM)) {
            current.setX(instruction.getOperand1());
            current.setxMem(null);
            Integer immValue = getImmValue(instruction.getOperand1());
            if(immValue != null) {
               current.setFlagZ(immValue == 0);
               current.setFlagN(immValue > 127);
            }
         }
         if(mnemnonic.equals("ldx") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setxMem(instruction.getOperand1());
            current.setX(null);
         }
         if(mnemnonic.equals("stx") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setxMem(instruction.getOperand1());
            if(instruction.getOperand1().equals(current.getaMem())) current.setaMem(null);
            if(instruction.getOperand1().equals(current.getyMem())) current.setyMem(null);
            if(instruction.getOperand1().equals(current.getzMem())) current.setzMem(null);
         }
         if(mnemnonic.equals("ldy") && addressingMode.equals(CpuAddressingMode.IMM)) {
            current.setY(instruction.getOperand1());
            current.setyMem(null);
            Integer immValue = getImmValue(instruction.getOperand1());
            if(immValue != null) {
               current.setFlagZ(immValue == 0);
               current.setFlagN(immValue > 127);
            }
         }
         if(mnemnonic.equals("ldy") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setyMem(instruction.getOperand1());
         }
         if(mnemnonic.equals("sty") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setyMem(instruction.getOperand1());
            if(instruction.getOperand1().equals(current.getaMem())) current.setaMem(null);
            if(instruction.getOperand1().equals(current.getxMem())) current.setxMem(null);
            if(instruction.getOperand1().equals(current.getzMem())) current.setzMem(null);
         }
         if(mnemnonic.equals("ldz") && addressingMode.equals(CpuAddressingMode.IMM)) {
            current.setZ(instruction.getOperand1());
            current.setzMem(null);
            Integer immValue = getImmValue(instruction.getOperand1());
            if(immValue != null) {
               current.setFlagZ(immValue == 0);
               current.setFlagN(immValue > 127);
            }
         }
         if(mnemnonic.equals("ldz") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setzMem(instruction.getOperand1());
         }
         if(mnemnonic.equals("stz") && (addressingMode.equals(CpuAddressingMode.ZP) || addressingMode.equals(CpuAddressingMode.ABS))) {
            current.setzMem(instruction.getOperand1());
            if(instruction.getOperand1().equals(current.getaMem())) current.setaMem(null);
            if(instruction.getOperand1().equals(current.getxMem())) current.setxMem(null);
            if(instruction.getOperand1().equals(current.getyMem())) current.setyMem(null);
         }
         if(mnemnonic.equals("txa")) {
            current.setA(current.getX());
            current.setaMem(current.getxMem());
         }
         if(mnemnonic.equals("tax")) {
            current.setX(current.getA());
            current.setxMem(current.getaMem());
         }
         if(mnemnonic.equals("tya")) {
            current.setA(current.getY());
            current.setaMem(current.getyMem());
         }
         if(mnemnonic.equals("tay")) {
            current.setY(current.getA());
            current.setyMem(current.getaMem());
         }
         if(mnemnonic.equals("tza")) {
            current.setA(current.getZ());
            current.setaMem(current.getzMem());
         }
         if(mnemnonic.equals("taz")) {
            current.setZ(current.getA());
            current.setzMem(current.getaMem());
         }
         if(mnemnonic.equals("sec")) {
            current.setFlagC(Boolean.TRUE);
         }
         if(mnemnonic.equals("clc")) {
            current.setFlagC(Boolean.FALSE);
         }
      }
      return current;
   }

   private void handleClobber(AsmRegisterValues current, CpuClobber cpuClobber) {
      if(cpuClobber.isRegisterA()) {
         current.setA(null);
         current.setaMem(null);
      }
      if(cpuClobber.isRegisterX()) {
         current.setX(null);
         current.setxMem(null);
      }
      if(cpuClobber.isRegisterY()) {
         current.setY(null);
         current.setyMem(null);
      }
      if(cpuClobber.isRegisterZ()) {
         current.setZ(null);
         current.setzMem(null);
      }
      if(cpuClobber.isFlagC()) {
         current.setFlagC(null);
      }
      if(cpuClobber.isFlagN()) {
         current.setFlagN(null);
      }
      if(cpuClobber.isFlagV()) {
         current.setFlagV(null);
      }
      if(cpuClobber.isFlagZ()) {
         current.setFlagZ(null);
      }
   }

   /**
    * Known values of registers/flags at an instruction. null where value is unknown.
    */
   public static class AsmRegisterValues {
      private Boolean flagC;
      private Boolean flagV;
      private Boolean flagN;
      private Boolean flagZ;
      private String a;
      private String x;
      private String y;
      private String z;
      private String aMem;
      private String xMem;
      private String yMem;
      private String zMem;

      public AsmRegisterValues() {
      }

      public AsmRegisterValues(AsmRegisterValues original) {
         this.a = original.getA();
         this.x = original.getX();
         this.y = original.getY();
         this.z = original.getZ();
         this.flagZ = original.getFlagZ();
         this.flagC = original.getFlagC();
         this.flagV = original.getFlagV();
         this.flagN = original.getFlagN();
         this.aMem = original.getaMem();
         this.xMem = original.getxMem();
         this.yMem = original.getyMem();
         this.zMem = original.getzMem();
      }

      public String getA() {
         return a;
      }

      public void setA(String a) {
         this.a = a;
      }

      public String getaMem() {
         return aMem;
      }

      public void setaMem(String aMem) {
         this.aMem = aMem;
      }

      public String getX() {
         return x;
      }

      public void setX(String x) {
         this.x = x;
      }

      public String getY() {
         return y;
      }

      public void setY(String y) {
         this.y = y;
      }

      public String getZ() {
         return z;
      }

      public void setZ(String z) {
         this.z = z;
      }

      public Boolean getFlagC() {
         return flagC;
      }

      public void setFlagC(Boolean flagC) {
         this.flagC = flagC;
      }

      public Boolean getFlagV() {
         return flagV;
      }

      public void setFlagV(Boolean flagV) {
         this.flagV = flagV;
      }

      public Boolean getFlagN() {
         return flagN;
      }

      public void setFlagN(Boolean flagN) {
         this.flagN = flagN;
      }

      public Boolean getFlagZ() {
         return flagZ;
      }

      public void setFlagZ(Boolean flagZ) {
         this.flagZ = flagZ;
      }

      public String getxMem() {
         return xMem;
      }

      public void setxMem(String xMem) {
         this.xMem = xMem;
      }

      public String getyMem() {
         return yMem;
      }

      public void setyMem(String yMem) {
         this.yMem = yMem;
      }

      public String getzMem() {
         return zMem;
      }

      public void setzMem(String zMem) {
         this.zMem = zMem;
      }


   }

}
