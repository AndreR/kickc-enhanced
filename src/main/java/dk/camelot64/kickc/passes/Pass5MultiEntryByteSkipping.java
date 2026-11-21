package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuClobber;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.ConstantNotLiteral;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.values.ConstantChar;
import dk.camelot64.kickc.model.values.ConstantInteger;
import dk.camelot64.kickc.model.values.ConstantLiteral;
import dk.camelot64.kickc.model.values.ScopeRef;

import java.util.*;

/**
 * Multi-Entry Byte-Skipping via BIT $xxxx (.byte $2c).
 *
 * Pattern B (Branch Byte-Skipping):
 * In if-else sequences where both branches load immediate values into the same register
 * before merging:
 *   LDA #val1
 *   JMP target
 *   label:
 *   LDA #val2
 *   target:
 * The JMP target (which jumps over a 2-byte immediate instruction) is replaced by .byte $2c (BIT abs).
 * When falling through, the CPU executes BIT $xxxx, consuming the 2-byte LDA #val2 as an operand
 * and skipping it in 4 cycles without altering register A, X, or Y.
 * Saves 2 bytes (1 byte instead of 3 bytes for JMP) and eliminates jump overhead.
 */
public class Pass5MultiEntryByteSkipping extends Pass5AsmOptimization {

   private static class ProcedureData {
      final String name;
      final List<AsmChunk> chunks = new ArrayList<>();
      final List<AsmInstruction> instructions = new ArrayList<>();

      ProcedureData(String name) {
         this.name = name;
      }
   }

   public Pass5MultiEntryByteSkipping(Program program) {
      super(program);
   }

   private static Long parseConstValue(String val) {
      if(val == null) return null;
      String t = val.trim();
      if(t.startsWith("#")) t = t.substring(1).trim();
      if(t.startsWith("<") || t.startsWith(">")) return null; // Reject address lo/hi
      if(t.startsWith("'") && t.endsWith("'") && t.length() >= 3) {
         return (long) t.charAt(1);
      }
      try {
         if(t.startsWith("$")) {
            return Long.parseLong(t.substring(1), 16);
         } else if(t.startsWith("0x") || t.startsWith("0X")) {
            return Long.parseLong(t.substring(2), 16);
         } else if(t.startsWith("%")) {
            return Long.parseLong(t.substring(1), 2);
         } else {
            return Long.parseLong(t);
         }
      } catch(NumberFormatException e) {
         return null;
      }
   }

   private static boolean isImmediateLoad(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      if(!("lda".equals(mnem) || "ldx".equals(mnem) || "ldy".equals(mnem))) return false;
      return CpuAddressingMode.IMM.equals(inst.getCpuOpcode().getAddressingMode());
   }

   private Long resolveConstValue(String val, String scope, Map<String, String> asmConstants, CanonicalResolver resolver) {
      Long direct = parseConstValue(val);
      if(direct != null) return direct;
      if(val == null) return null;
      String t = val.trim();
      if(t.startsWith("#")) t = t.substring(1).trim();
      if(t.isEmpty() || t.startsWith("<") || t.startsWith(">")) return null;

      // 1. Resolve via ASM .const declarations (local scope first, then global)
      if(asmConstants != null) {
         String cur = t;
         for(int hop = 0; hop < 8; hop++) {
            String mapped = null;
            if(scope != null && !scope.isEmpty()) {
               mapped = asmConstants.get(scope + "." + cur);
            }
            if(mapped == null) {
               mapped = asmConstants.get(cur);
            }
            if(mapped == null) break;
            Long parsed = parseConstValue(mapped);
            if(parsed != null) return parsed;
            cur = mapped.trim();
            if(cur.startsWith("#")) cur = cur.substring(1).trim();
            if(cur.startsWith("<") || cur.startsWith(">")) return null;
         }
      }

      // 2. Resolve via CanonicalResolver (.label numeric aliases)
      if(resolver != null) {
         String resolved = resolver.resolve(t);
         if(resolved != null && !resolved.equals(t)) {
            Long parsed = parseConstValue(resolved);
            if(parsed != null) return parsed;
         }
      }

      // 3. Resolve via ProgramScope constant symbol (e.g. const uint8_t RED = 2)
      if(getProgram() != null && getProgram().getScope() != null) {
         Symbol sym = null;
         if(scope != null && !scope.isEmpty()) {
            Symbol procSym = getProgram().getScope().findSymbol(scope);
            if(procSym instanceof Procedure proc) {
               sym = proc.findSymbol(t);
            }
         }
         if(sym == null) {
            sym = getProgram().getScope().findSymbol(t);
         }
         if(sym instanceof Variable v && v.isKindConstant() && v.getInitValue() != null) {
            try {
               ConstantLiteral lit = v.getInitValue().calculateLiteral(getProgram().getScope());
               if(lit instanceof ConstantInteger ci) {
                  return ci.getInteger();
               } else if(lit instanceof ConstantChar cc) {
                  return cc.getInteger();
               }
            } catch(ConstantNotLiteral ignored) {
            }
         }
      }
      return null;
   }

   /**
    * CF-04: Verify that skipping the 2-byte immediate load via .byte $2c (BIT $xxxx)
    * cannot perform a dummy bus read on hardware I/O space ($D000-$DFFF or $0000-$0001).
    * BIT $xxxx forms address (immByte << 8) | opcodeByte.
    */
   private boolean isSafeByteSkipImmediate(AsmInstruction inst, String scope, Map<String, String> asmConstants, CanonicalResolver resolver) {
      if(!isImmediateLoad(inst) || inst.getLineBytes() != 2) return false;
      Long val = resolveConstValue(inst.getOperand1(), scope, asmConstants, resolver);
      if(val == null) return false; // Reject unresolved symbolic/label operands like #<IO or #>IO
      int hiByte = (int) (val & 0xff);
      if(hiByte >= 0xd0 && hiByte <= 0xdf) {
         return false; // $D000-$DFFF (VIC-II, SID, CIA, I/O1/I/O2 cartridge latches)
      }
      int[] opBytes = inst.getCpuOpcode().getOpcode();
      int loByte = (opBytes != null && opBytes.length > 0) ? (opBytes[0] & 0xff) : 0;
      int dummyAddr = (hiByte << 8) | loByte;
      if(dummyAddr <= 0x0001) {
         return false; // CPU port $0000-$0001
      }
      return true;
   }

   private static boolean hasDisqualifyingLines(List<AsmChunk> chunks) {
      for(AsmChunk ch : chunks) {
         for(AsmLine l : ch.getLines()) {
            if(l instanceof AsmSegment || l instanceof AsmInlineKickAsm || l instanceof AsmConstant ||
               l instanceof AsmLabel || l instanceof AsmDataNumeric || l instanceof AsmDataString ||
               l instanceof AsmDataZeroFill || l instanceof AsmDataKickAsm || l instanceof AsmDataAlignment ||
               l instanceof AsmLoopAlign || l instanceof AsmSetPc) {
               return true;
            }
         }
      }
      return false;
   }

   private static boolean isConditionalBranch(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      return mnem.equals("beq") || mnem.equals("bne") || mnem.equals("bpl") || mnem.equals("bmi") ||
             mnem.equals("bvc") || mnem.equals("bvs") || mnem.equals("bcc") || mnem.equals("bcs");
   }

   private static boolean matchesLabel(String labelName, String target, String scope) {
      if(labelName == null || target == null) return false;
      if(labelName.equals(target)) return true;
      if(scope != null) {
         if((scope + "::" + target).equals(labelName)) return true;
         if((scope + "::" + labelName).equals(target)) return true;
      }
      if(target.endsWith("::" + labelName)) return true;
      if(labelName.endsWith("::" + target)) return true;
      return false;
   }

   @Override
   public boolean optimize() {
      boolean overallModified = false;
      boolean passModified = true;

      while(passModified) {
         passModified = false;

         List<LineRef> allLines = flattenLines(getAsmProgram());
         Map<String, CanonicalResolver> scopeResolvers = buildScopeResolvers(allLines);
         Map<String, String> asmConstants = new HashMap<>();
         for(LineRef ref : allLines) {
            if(ref.line instanceof AsmConstant c && c.getName() != null && c.getValue() != null) {
               String cname = c.getName().trim();
               String cval = c.getValue().trim();
               if(ref.scope != null && !ref.scope.isEmpty()) {
                  asmConstants.put(ref.scope + "." + cname, cval);
               } else {
                  asmConstants.put(cname, cval);
               }
            }
         }

         // Scan for Pattern B (Branch Byte-Skipping)
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref1 = allLines.get(i);
            if(!(ref1.line instanceof AsmInstruction)) continue;
            AsmInstruction inst1 = (AsmInstruction) ref1.line;
            if(!isImmediateLoad(inst1) || inst1.isNoOptimize()) continue;
            String reg1 = inst1.getCpuOpcode().getMnemonic();

            // Next instruction must be JMP
            int idxJmp = -1;
            for(int j = i + 1; j < allLines.size(); j++) {
               AsmLine l = allLines.get(j).line;
               if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
               if(l instanceof AsmInstruction) {
                  idxJmp = j;
                  break;
               }
               break;
            }
            if(idxJmp == -1) continue;

            LineRef refJmp = allLines.get(idxJmp);
            AsmInstruction instJmp = (AsmInstruction) refJmp.line;
            if(instJmp.getCpuOpcode() == null || instJmp.isNoOptimize()) continue;
            if(!"jmp".equals(instJmp.getCpuOpcode().getMnemonic())) continue;
            if(!CpuAddressingMode.ABS.equals(instJmp.getCpuOpcode().getAddressingMode())) continue;

            String jumpTarget = instJmp.getOperandJumpTarget();
            if(jumpTarget == null) continue;

            // Find next instruction after JMP (skipping comments and labels)
            int idx2 = -1;
            boolean hadScopeBoundary = false;
            for(int j = idxJmp + 1; j < allLines.size(); j++) {
               AsmLine l = allLines.get(j).line;
               if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl || l instanceof AsmLabel) continue;
               if(l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) {
                  hadScopeBoundary = true;
                  break;
               }
               if(l instanceof AsmInstruction) {
                  idx2 = j;
                  break;
               }
               break;
            }
            if(idx2 == -1 || hadScopeBoundary) continue;

            LineRef ref2 = allLines.get(idx2);
            AsmInstruction inst2 = (AsmInstruction) ref2.line;
            CanonicalResolver scopeRes2 = scopeResolvers.getOrDefault(ref2.scope != null ? ref2.scope : "", scopeResolvers.get(""));
            if(!isSafeByteSkipImmediate(inst2, ref2.scope, asmConstants, scopeRes2) || inst2.isNoOptimize()) continue;
            String reg2 = inst2.getCpuOpcode().getMnemonic();
            if(!reg1.equals(reg2)) continue; // Must load into same register

            // Right after inst2, the target label must be present
            boolean foundTargetLabel = false;
            for(int j = idx2 + 1; j < allLines.size(); j++) {
               AsmLine l = allLines.get(j).line;
               if(l instanceof AsmComment || l instanceof AsmConstant || l instanceof AsmLabelDecl) continue;
               if(l instanceof AsmLabel) {
                  String lbl = ((AsmLabel) l).getLabel();
                  if(matchesLabel(lbl, jumpTarget, refJmp.scope)) {
                     foundTargetLabel = true;
                     continue;
                  }
               }
               if(l instanceof AsmInstruction || l instanceof AsmScopeBegin || l instanceof AsmScopeEnd) {
                  break;
               }
            }

            if(!foundTargetLabel) continue;

            // CF-04: Verify full N/V/Z flag liveness after target (BIT $xxxx clobbers N, V, Z!)
            if((getLiveFlagsAfter(allLines, idx2, refJmp.scope, getProgram()) & FLAG_NZV) != 0) {
               continue;
            }

            // All checks passed! Replace JMP with .byte $2c
            getLog().append("Optimizing JMP " + jumpTarget + " to .byte $2c (BIT $xxxx byte-skipper over " + inst2.getAsm() + ")");

            AsmInlineKickAsm byteSkipper = new AsmInlineKickAsm(".byte $2c\n", 1L, 4L, new CpuClobber("nzv"));
            int chunkIdx = refJmp.chunk.getLines().indexOf(instJmp);
            if(chunkIdx != -1) {
               refJmp.chunk.getLines().set(chunkIdx, byteSkipper);
               allLines.set(idxJmp, new LineRef(idxJmp, refJmp.chunk, byteSkipper, refJmp.scope));
               passModified = true;
               overallModified = true;
            }
         }

         if(passModified) continue;

         // Scan for Pattern A (Procedure-Level Entry-Point Merging)
         List<AsmChunk> allChunks = getAsmProgram().getChunkList();
         List<ProcedureData> procedures = new ArrayList<>();
         Map<ProcedureData, int[]> procChunkRange = new IdentityHashMap<>();
         ProcedureData currentProc = null;
         for(int ci = 0; ci < allChunks.size(); ci++) {
            AsmChunk chunk = allChunks.get(ci);
            String scope = chunk.getScopeLabel();
            if(scope != null && !scope.equals(ScopeRef.ROOT.getFullName())) {
               if(currentProc == null || !currentProc.name.equals(scope)) {
                  currentProc = new ProcedureData(scope);
                  procedures.add(currentProc);
                  procChunkRange.put(currentProc, new int[]{ci, ci});
               } else {
                  procChunkRange.get(currentProc)[1] = ci;
               }
               currentProc.chunks.add(chunk);
               for(AsmLine l : chunk.getLines()) {
                  if(l instanceof AsmInstruction) {
                     currentProc.instructions.add((AsmInstruction) l);
                  }
               }
            } else {
               currentProc = null;
            }
         }

         for(int p = 0; p < procedures.size() - 1; p++) {
            ProcedureData p1 = procedures.get(p);
            ProcedureData p2 = procedures.get(p + 1);

            // CF-04: Strict chunk adjacency — no intervening global/data/segment chunks between p1 and p2
            int[] r1 = procChunkRange.get(p1);
            int[] r2 = procChunkRange.get(p2);
            if(r1 == null || r2 == null || r1[1] + 1 != r2[0]) continue;

            List<AsmInstruction> insts1 = p1.instructions;
            List<AsmInstruction> insts2 = p2.instructions;

            if(insts1.size() < 2 || insts2.size() != insts1.size()) continue;

            // Neither p1 nor p2 may contain segments, data, labels, constants, or inline KickAsm
            if(hasDisqualifyingLines(p1.chunks) || hasDisqualifyingLines(p2.chunks)) continue;

            AsmInstruction first1 = insts1.get(0);
            AsmInstruction first2 = insts2.get(0);

            CanonicalResolver res1 = scopeResolvers.getOrDefault(p1.name, scopeResolvers.get(""));
            CanonicalResolver res2 = scopeResolvers.getOrDefault(p2.name, scopeResolvers.get(""));
            if(!isImmediateLoad(first1) || !isSafeByteSkipImmediate(first2, p2.name, asmConstants, res2)) continue;
            if(!first1.getCpuOpcode().getMnemonic().equals(first2.getCpuOpcode().getMnemonic())) continue;
            if(first1.getLineBytes() != 2 || first2.getLineBytes() != 2) continue;

            // Operands must be pure scalar/numeric constants (or resolved .const symbols), NOT label references like #<msg or #>msg
            Long const1 = resolveConstValue(first1.getOperand1(), p1.name, asmConstants, res1);
            Long const2 = resolveConstValue(first2.getOperand1(), p2.name, asmConstants, res2);
            if(const1 == null || const2 == null) continue;

            // Last instruction must be RTS
            AsmInstruction last1 = insts1.get(insts1.size() - 1);
            AsmInstruction last2 = insts2.get(insts2.size() - 1);
            if(last1.getCpuOpcode() == null || last2.getCpuOpcode() == null) continue;
            if(!"rts".equals(last1.getCpuOpcode().getMnemonic()) || !"rts".equals(last2.getCpuOpcode().getMnemonic())) continue;

            // Verify N/V/Z flags set by first1/first2 are not needed by insts2[1..]
            int first2AllIdx = -1;
            for(int li = 0; li < allLines.size(); li++) {
               if(allLines.get(li).line == first2) {
                  first2AllIdx = li;
                  break;
               }
            }
            if(first2AllIdx == -1 || (getLiveFlagsAfter(allLines, first2AllIdx, p2.name, getProgram()) & FLAG_NZV) != 0) {
               continue;
            }

            // Check if remaining instructions (index 1 to end) are canonically identical across scopes
            boolean identical = true;
            for(int k = 1; k < insts1.size(); k++) {
               AsmInstruction i1 = insts1.get(k);
               AsmInstruction i2 = insts2.get(k);
               if(!Objects.equals(i1.getCpuOpcode(), i2.getCpuOpcode())) {
                  identical = false;
                  break;
               }
               if(!Objects.equals(i1.getOperand1(), i2.getOperand1())) {
                  identical = false;
                  break;
               }
               if(!Objects.equals(i1.getOperand2(), i2.getOperand2())) {
                  identical = false;
                  break;
               }
               // CF-04: Canonical ZP/symbol equality check (e.g. p1.return = $02 vs p2.return = $04 must NOT match!)
               if(i1.getOperand1() != null) {
                  String op1 = i1.getOperand1().trim();
                  if(op1.contains(p1.name + ".") || op1.contains(p2.name + ".")) {
                     identical = false;
                     break;
                  }
                  String c1 = res1 != null ? res1.resolve(op1) : op1;
                  String c2 = res2 != null ? res2.resolve(op1) : op1;
                  if(!Objects.equals(c1, c2)) {
                     identical = false;
                     break;
                  }
               }
            }
            if(!identical) continue;

            // Merge! In p1: keep first1, append .byte $2c, remove all subsequent instructions
            getLog().append("Merging procedure entries: " + p1.name + " -> " + p2.name + " via .byte $2c");

            // Find chunk containing first1
            AsmChunk chunkFirst = null;
            int idxFirst = -1;
            for(AsmChunk ch : p1.chunks) {
               idxFirst = ch.getLines().indexOf(first1);
               if(idxFirst != -1) {
                  chunkFirst = ch;
                  break;
               }
            }
            if(chunkFirst == null) continue;

            // In chunkFirst: retain lines up to first1, add .byte $2c, remove remaining lines (except ScopeEnd)
            List<AsmLine> newLines = new ArrayList<>();
            for(int k = 0; k <= idxFirst; k++) {
               newLines.add(chunkFirst.getLines().get(k));
            }
            newLines.add(new AsmInlineKickAsm(".byte $2c\n", 1L, 4L, new CpuClobber("nzv")));
            for(int k = idxFirst + 1; k < chunkFirst.getLines().size(); k++) {
               AsmLine l = chunkFirst.getLines().get(k);
               if(l instanceof AsmScopeEnd) {
                  newLines.add(l);
               }
            }
            chunkFirst.getLines().clear();
            chunkFirst.getLines().addAll(newLines);

            // In subsequent chunks of p1: remove all instructions, retain AsmScopeEnd
            boolean foundFirstChunk = false;
            for(AsmChunk ch : p1.chunks) {
               if(ch == chunkFirst) {
                  foundFirstChunk = true;
                  continue;
               }
               if(foundFirstChunk) {
                  List<AsmLine> chLines = new ArrayList<>();
                  for(AsmLine l : ch.getLines()) {
                     if(l instanceof AsmScopeEnd) {
                        chLines.add(l);
                     }
                  }
                  ch.getLines().clear();
                  ch.getLines().addAll(chLines);
               }
            }

            passModified = true;
            overallModified = true;
            break;
         }
      }

      return overallModified;
   }
}
