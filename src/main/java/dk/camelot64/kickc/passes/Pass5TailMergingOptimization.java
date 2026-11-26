package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;

import java.util.*;

/**
 * Pass 5 Tail-Merging & Cross-Jumping Optimization.
 *
 * Performs two size-reducing control-flow merges without crossing segment boundaries:
 * A. Intra-scope suffix tail-merging: merges common >= 4-byte (>= 2 instruction) suffixes
 *    ending in RTS, RTI, or absolute JMP within the same procedure scope and segment.
 * B. Cross-procedure shared epilogues (cross-jumping): merges common >= 4-byte (>= 2 instruction)
 *    scope-independent epilogue suffixes ending in RTS or RTI across procedures in the same segment.
 */
public class Pass5TailMergingOptimization extends Pass5AsmOptimization {

   private int tailLabelCounter = 0;
   private int epilogueLabelCounter = 0;

   public Pass5TailMergingOptimization(Program program) {
      super(program);
   }

   private static final class TailSeq {
      final String scope;
      final String segment;
      final List<LineRef> instRefs;
      final List<Integer> scopeLineIndices;
      final String precedingNamedLabel;
      final int totalBytes;

      TailSeq(String scope, String segment, List<LineRef> instRefs, List<Integer> scopeLineIndices,
              String precedingNamedLabel, int totalBytes) {
         this.scope = scope;
         this.segment = segment;
         this.instRefs = instRefs;
         this.scopeLineIndices = scopeLineIndices;
         this.precedingNamedLabel = precedingNamedLabel;
         this.totalBytes = totalBytes;
      }

      AsmInstruction lastInst() {
         return (AsmInstruction) instRefs.get(instRefs.size() - 1).line;
      }

      AsmInstruction secondLastInst() {
         return (AsmInstruction) instRefs.get(instRefs.size() - 2).line;
      }
   }

   private static final class EpilogueKey {
      final String segment;
      final CpuOpcode lastOp;
      final CpuOpcode prevOp;
      final String prevOp1;

      EpilogueKey(String segment, AsmInstruction prev, AsmInstruction last) {
         this.segment = segment;
         this.lastOp = last.getCpuOpcode();
         this.prevOp = prev.getCpuOpcode();
         this.prevOp1 = prev.getOperand1();
      }

      @Override
      public boolean equals(Object o) {
         if(this == o) return true;
         if(!(o instanceof EpilogueKey other)) return false;
         return Objects.equals(segment, other.segment)
               && Objects.equals(lastOp, other.lastOp)
               && Objects.equals(prevOp, other.prevOp)
               && Objects.equals(prevOp1, other.prevOp1);
      }

      @Override
      public int hashCode() {
         return Objects.hash(segment, lastOp, prevOp, prevOp1);
      }
   }

   private static boolean instructionsEqual(AsmInstruction a, AsmInstruction b) {
      if(a == b) return true;
      if(a == null || b == null) return false;
      if(a.getCpuOpcode() == null || !a.getCpuOpcode().equals(b.getCpuOpcode())) return false;
      return Objects.equals(a.getOperand1(), b.getOperand1())
            && Objects.equals(a.getOperand2(), b.getOperand2())
            && Objects.equals(a.getOperand3(), b.getOperand3());
   }

   private static boolean isValidTailTerminator(AsmInstruction inst, boolean allowJmp) {
      if(inst == null || inst.isNoOptimize() || inst.getCpuOpcode() == null) return false;
      CpuOpcode op = inst.getCpuOpcode();
      String mnem = op.getMnemonic();
      if("rts".equals(mnem) || "rti".equals(mnem)) {
         return true;
      }
      if(allowJmp && "jmp".equals(mnem) && CpuAddressingMode.ABS.equals(op.getAddressingMode())) {
         String target = inst.getOperand1();
         return target != null && !target.trim().startsWith("!");
      }
      return false;
   }

   private static boolean hasRelativeBangOperand(AsmInstruction inst) {
      String jt = inst.getOperandJumpTarget();
      if(jt != null && jt.trim().startsWith("!")) return true;
      String op1 = inst.getOperand1();
      return op1 != null && op1.trim().startsWith("!");
   }

   private static boolean isNumericLiteral(String s) {
      if(s == null || s.isEmpty()) return false;
      String t = s.trim();
      if(t.startsWith("+") || t.startsWith("-")) {
         t = t.substring(1).trim();
         if(t.isEmpty()) return false;
      }
      if(t.startsWith("$") && t.length() > 1) {
         for(int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
         }
         return true;
      }
      if((t.startsWith("0x") || t.startsWith("0X")) && t.length() > 2) {
         for(int i = 2; i < t.length(); i++) {
            char c = t.charAt(i);
            if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
         }
         return true;
      }
      if(t.startsWith("%") && t.length() > 1) {
         for(int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if(c != '0' && c != '1') return false;
         }
         return true;
      }
      if(t.startsWith("'") && t.endsWith("'") && t.length() == 3) {
         return true;
      }
      for(int i = 0; i < t.length(); i++) {
         char c = t.charAt(i);
         if(c < '0' || c > '9') return false;
      }
      return true;
   }

   /**
    * Checks that an instruction is safe to share across procedure scopes in the same segment:
    * no relative branches, no jumps/calls, no scope-local symbols or labels.
    */
   private static boolean isScopeIndependentInstruction(AsmInstruction inst, Set<String> scopeDeclaredSymbols, Set<String> globalDeclaredSymbols) {
      if(inst == null || inst.isNoOptimize() || inst.getCpuOpcode() == null) return false;
      CpuOpcode op = inst.getCpuOpcode();
      CpuAddressingMode mode = op.getAddressingMode();
      String mnem = op.getMnemonic();
      if("rts".equals(mnem) || "rti".equals(mnem)) {
         return true;
      }
      if(op.isJump() || CpuAddressingMode.REL.equals(mode)) {
         return false;
      }
      if(inst.getOperand2() != null || inst.getOperand3() != null) {
         return false;
      }
      String op1 = inst.getOperand1();
      if(op1 == null) {
         return CpuAddressingMode.NON.equals(mode);
      }
      String t = op1.trim();
      if(t.startsWith(".z ")) {
         t = t.substring(3).trim();
      }
      if(t.startsWith("!")) {
         return false;
      }
      if(CpuAddressingMode.IMM.equals(mode)) {
         if(t.startsWith("#")) t = t.substring(1).trim();
         return isNumericLiteral(t);
      }
      if(CpuAddressingMode.ZP.equals(mode) || CpuAddressingMode.ABS.equals(mode)) {
         String base = getBaseVar(t);
         if(parseHexOrDecAddress(base) != null) {
            return true;
         }
         if(base.isEmpty() || base.contains(".")) return false;
         return !scopeDeclaredSymbols.contains(base) && globalDeclaredSymbols.contains(base);
      }
      return false;
   }

   private static String findImmediatePrecedingNamedLabel(List<LineRef> lines, int firstInstIdx) {
      for(int p = firstInstIdx - 1; p >= 0; p--) {
         AsmLine line = lines.get(p).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
            continue;
         }
         if(line instanceof AsmLabel lbl) {
            String name = lbl.getLabel();
            if(name != null && !name.startsWith("!")) {
               return name;
            }
         }
         break;
      }
      return null;
   }

   private String nextUniqueTailLabel(Set<String> existingLabels) {
      String candidate;
      do {
         candidate = "__tail_" + (++tailLabelCounter);
      } while(!existingLabels.add(candidate));
      return candidate;
   }

   private String nextUniqueEpilogueLabel(Set<String> existingLabels, String scope) {
      String candidate;
      do {
         candidate = "__epilogue_" + (++epilogueLabelCounter);
      } while(!existingLabels.add(scope + "::" + candidate));
      return candidate;
   }

   private static int commonSuffixInstructionCount(List<LineRef> a, List<LineRef> b) {
      int ia = a.size() - 1;
      int ib = b.size() - 1;
      int matched = 0;
      while(ia >= 0 && ib >= 0) {
         AsmInstruction instA = (AsmInstruction) a.get(ia).line;
         AsmInstruction instB = (AsmInstruction) b.get(ib).line;
         if(!instructionsEqual(instA, instB)) {
            break;
         }
         matched++;
         ia--;
         ib--;
      }
      return matched;
   }

   private static int suffixByteSize(List<LineRef> insts, int matchCount) {
      int bytes = 0;
      for(int i = insts.size() - matchCount; i < insts.size(); i++) {
         bytes += insts.get(i).line.getLineBytes();
      }
      return bytes;
   }

   @Override
   public boolean optimize() {
      CpuOpcode jmpAbsOpcode = getAsmProgram().getTargetCpu().getCpu65xx().getOpcode("jmp", CpuAddressingMode.ABS, false);
      if(jmpAbsOpcode == null) {
         return false;
      }

      boolean overallModified = false;

      // Collect existing label names for collision-free label synthesis
      Set<String> existingSimpleLabels = new HashSet<>();
      Set<String> existingScopedLabels = new HashSet<>();
      List<LineRef> initialLines = getSharedAllLines();
      for(LineRef ref : initialLines) {
         if(ref.line instanceof AsmLabel lbl && lbl.getLabel() != null) {
            existingSimpleLabels.add(lbl.getLabel());
            String sc = ref.scope != null ? ref.scope : "";
            existingScopedLabels.add(sc + "::" + lbl.getLabel());
         }
      }

      // -----------------------------------------------------------------
      // Part A: Intra-Scope Suffix Tail-Merging
      // -----------------------------------------------------------------
      boolean partAModified = false;
      Map<String, List<LineRef>> scopeLinesMap = getSharedScopeLinesMap(initialLines);
      Map<LineRef, String> lineSegmentMap = buildLineSegmentMap(initialLines);

      for(Map.Entry<String, List<LineRef>> entry : scopeLinesMap.entrySet()) {
         String scope = entry.getKey();
         if(scope == null || scope.isEmpty() || !isScopeDirty(scope)) {
            continue;
         }
         // Work on a mutable copy of this scope's lines while iterating intra-scope merges
         List<LineRef> sLines = new ArrayList<>(entry.getValue());
         boolean scopeChanged = false;
         while(mergeBestIntraScopeTail(sLines, scope, lineSegmentMap, jmpAbsOpcode, existingSimpleLabels)) {
            scopeChanged = true;
         }
         if(scopeChanged) {
            markScopeDirty(scope);
            partAModified = true;
            overallModified = true;
         }
      }

      if(partAModified) {
         invalidateRoundStructure();
      }

      // -----------------------------------------------------------------
      // Part B: Cross-Procedure Shared Epilogues (Cross-Jumping)
      // -----------------------------------------------------------------
      List<LineRef> allLines = getSharedAllLines();
      if(mergeCrossProcedureEpilogues(allLines, jmpAbsOpcode, existingScopedLabels)) {
         invalidateRoundStructure();
         overallModified = true;
      }

      return overallModified;
   }

   private static Map<LineRef, String> buildLineSegmentMap(List<LineRef> allLines) {
      Map<LineRef, String> map = new IdentityHashMap<>(allLines.size());
      String currentSegment = "Code";
      for(LineRef ref : allLines) {
         if(ref.line instanceof AsmSegment seg && seg.getName() != null) {
            currentSegment = seg.getName();
         }
         map.put(ref, currentSegment);
      }
      return map;
   }

   private List<TailSeq> extractIntraScopeTails(List<LineRef> sLines, String scope, Map<LineRef, String> lineSegmentMap) {
      List<TailSeq> tails = new ArrayList<>();
      for(int endIdx = 0; endIdx < sLines.size(); endIdx++) {
         LineRef endRef = sLines.get(endIdx);
         if(!(endRef.line instanceof AsmInstruction endInst)) continue;
         if(!isValidTailTerminator(endInst, true)) continue;

         String seg = lineSegmentMap.getOrDefault(endRef, "Code");
         List<LineRef> reversedRefs = new ArrayList<>();
         List<Integer> reversedScopeIndices = new ArrayList<>();
         reversedRefs.add(endRef);
         reversedScopeIndices.add(endIdx);
         int totalBytes = endInst.getLineBytes();

         int firstInstScopeIdx = endIdx;
         for(int k = endIdx - 1; k >= 0; k--) {
            LineRef kRef = sLines.get(k);
            AsmLine line = kRef.line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
               continue;
            }
            if(line instanceof AsmInstruction inst) {
               if(inst.isNoOptimize() || inst.getCpuOpcode() == null || hasRelativeBangOperand(inst)) {
                  break;
               }
               String mnem = inst.getCpuOpcode().getMnemonic();
               if("rts".equals(mnem) || "rti".equals(mnem) || "jmp".equals(mnem)) {
                  break;
               }
               if(!Objects.equals(seg, lineSegmentMap.getOrDefault(kRef, "Code"))) {
                  break;
               }
               reversedRefs.add(kRef);
               reversedScopeIndices.add(k);
               totalBytes += inst.getLineBytes();
               firstInstScopeIdx = k;
               continue;
            }
            // Stop at AsmLabel, AsmInlineKickAsm, AsmDataNumeric, AsmSegment, AsmScopeBegin, etc.
            break;
         }

         if(reversedRefs.size() >= 2 && totalBytes >= 4) {
            Collections.reverse(reversedRefs);
            Collections.reverse(reversedScopeIndices);
            String preLabel = findImmediatePrecedingNamedLabel(sLines, firstInstScopeIdx);
            tails.add(new TailSeq(scope, seg, reversedRefs, reversedScopeIndices, preLabel, totalBytes));
         }
      }
      return tails;
   }

   private boolean mergeBestIntraScopeTail(List<LineRef> sLines, String scope, Map<LineRef, String> lineSegmentMap,
                                           CpuOpcode jmpAbsOpcode, Set<String> existingSimpleLabels) {
      List<TailSeq> tails = extractIntraScopeTails(sLines, scope, lineSegmentMap);
      if(tails.size() < 2) {
         return false;
      }

      int bestIdxA = -1;
      int bestIdxB = -1;
      int bestMatchCount = 0;
      int bestMatchBytes = 0;

      for(int i = 0; i < tails.size(); i++) {
         TailSeq a = tails.get(i);
         for(int j = i + 1; j < tails.size(); j++) {
            TailSeq b = tails.get(j);
            if(!Objects.equals(a.segment, b.segment)) continue;
            int matchCount = commonSuffixInstructionCount(a.instRefs, b.instRefs);
            if(matchCount < 2) continue;
            int matchBytes = suffixByteSize(a.instRefs, matchCount);
            if(matchBytes < 4) continue;
            if(matchBytes > bestMatchBytes || (matchBytes == bestMatchBytes && matchCount > bestMatchCount)) {
               bestMatchBytes = matchBytes;
               bestMatchCount = matchCount;
               bestIdxA = i;
               bestIdxB = j;
            }
         }
      }

      if(bestIdxA == -1) {
         return false;
      }

      TailSeq tailA = tails.get(bestIdxA);
      TailSeq tailB = tails.get(bestIdxB);

      // Prefer a tail where the common suffix covers the whole tail and already has a named entry label
      boolean aFullLabeled = (tailA.instRefs.size() == bestMatchCount) && tailA.precedingNamedLabel != null;
      boolean bFullLabeled = (tailB.instRefs.size() == bestMatchCount) && tailB.precedingNamedLabel != null;
      TailSeq canonical = (!aFullLabeled && bFullLabeled) ? tailB : tailA;
      int canonStartOffset = canonical.instRefs.size() - bestMatchCount;

      String targetLabel;
      if(canonStartOffset == 0 && canonical.precedingNamedLabel != null) {
         targetLabel = canonical.precedingNamedLabel;
      } else {
         targetLabel = nextUniqueTailLabel(existingSimpleLabels);
         LineRef canonStartRef = canonical.instRefs.get(canonStartOffset);
         AsmLabel lbl = new AsmLabel(targetLabel);
         canonStartRef.chunk.addLineBefore(canonStartRef.line, lbl);
         int canonScopeIdx = canonical.scopeLineIndices.get(canonStartOffset);
         LineRef lblRef = new LineRef(canonStartRef.chunk, lbl);
         lineSegmentMap.put(lblRef, canonical.segment);
         sLines.add(canonScopeIdx, lblRef);
         // Note: sLines indices shifted after inserting lblRef; adjust remaining TailSeq scope indices
         for(TailSeq t : tails) {
            for(int k = 0; k < t.scopeLineIndices.size(); k++) {
               int idx = t.scopeLineIndices.get(k);
               if(idx >= canonScopeIdx) {
                  t.scopeLineIndices.set(k, idx + 1);
               }
            }
         }
      }

      // Collect all tails (other than canonical) that share this exact suffix and rewrite them to jmp targetLabel
      List<TailSeq> duplicates = new ArrayList<>();
      for(TailSeq t : tails) {
         if(t == canonical) continue;
         if(!Objects.equals(t.segment, canonical.segment)) continue;
         int mc = commonSuffixInstructionCount(canonical.instRefs, t.instRefs);
         if(mc >= bestMatchCount) {
            duplicates.add(t);
         }
      }

      List<Integer> removedScopeIndices = new ArrayList<>();
      for(TailSeq dup : duplicates) {
         int dupStartOffset = dup.instRefs.size() - bestMatchCount;
         LineRef firstDupRef = dup.instRefs.get(dupStartOffset);
         AsmInstruction firstDupInst = (AsmInstruction) firstDupRef.line;

         getLog().append("Pass5TailMerging (intra-scope " + scope + "): merging " + bestMatchCount
               + " instructions (" + bestMatchBytes + " bytes) -> jmp " + targetLabel);

         firstDupInst.setCpuOpcode(jmpAbsOpcode);
         firstDupInst.setOperand1(targetLabel);
         firstDupInst.setOperand2(null);
         firstDupInst.setOperand3(null);

         for(int k = dupStartOffset + 1; k < dup.instRefs.size(); k++) {
            LineRef remRef = dup.instRefs.get(k);
            remRef.chunk.getLines().remove(remRef.line);
            removedScopeIndices.add(dup.scopeLineIndices.get(k));
         }
      }

      removedScopeIndices.sort(Collections.reverseOrder());
      for(int remIdx : removedScopeIndices) {
         sLines.remove(remIdx);
      }

      return !duplicates.isEmpty();
   }

   private boolean mergeCrossProcedureEpilogues(List<LineRef> allLines, CpuOpcode jmpAbsOpcode, Set<String> existingScopedLabels) {
      // Collect per-scope and global declared .label symbols to guarantee scope independence
      Map<String, Set<String>> scopeDeclaredSymbols = new HashMap<>();
      Set<String> globalDeclaredSymbols = new HashSet<>();
      Map<String, Boolean> segmentHasDirtyScope = new HashMap<>();
      String currentSegment = "Code";

      for(LineRef ref : allLines) {
         if(ref.line instanceof AsmSegment seg && seg.getName() != null) {
            currentSegment = seg.getName();
         }
         String sc = ref.scope != null ? ref.scope : "";
         if(!sc.isEmpty() && isScopeDirty(sc)) {
            segmentHasDirtyScope.put(currentSegment, Boolean.TRUE);
         }
         if(ref.line instanceof AsmLabelDecl decl) {
            String rawName = decl.getName().trim();
            int dot = rawName.lastIndexOf('.');
            String simple = dot != -1 ? rawName.substring(dot + 1) : rawName;
            if(sc.isEmpty()) {
               globalDeclaredSymbols.add(rawName);
               globalDeclaredSymbols.add(simple);
            } else {
               Set<String> set = scopeDeclaredSymbols.computeIfAbsent(sc, k -> new HashSet<>());
               set.add(rawName);
               set.add(simple);
            }
         } else if(ref.line instanceof AsmLabel lbl && !sc.isEmpty() && lbl.getLabel() != null) {
            scopeDeclaredSymbols.computeIfAbsent(sc, k -> new HashSet<>()).add(lbl.getLabel().trim());
         }
      }

      // Extract maximal scope-independent epilogues ending in RTS / RTI per segment
      Map<EpilogueKey, List<TailSeq>> buckets = new LinkedHashMap<>();
      currentSegment = "Code";
      for(int endIdx = 0; endIdx < allLines.size(); endIdx++) {
         LineRef endRef = allLines.get(endIdx);
         if(endRef.line instanceof AsmSegment seg && seg.getName() != null) {
            currentSegment = seg.getName();
         }
         if(!(endRef.line instanceof AsmInstruction endInst)) continue;
         if(!isValidTailTerminator(endInst, false)) continue; // RTS / RTI only
         String scope = endRef.scope;
         if(scope == null || scope.isEmpty()) continue;
         if(!Boolean.TRUE.equals(segmentHasDirtyScope.get(currentSegment))) continue;

         Set<String> declaredInScope = scopeDeclaredSymbols.getOrDefault(scope, Collections.emptySet());
         List<LineRef> reversedRefs = new ArrayList<>();
         reversedRefs.add(endRef);
         int totalBytes = endInst.getLineBytes();
         int firstInstGlobalIdx = endIdx;

         String seg = currentSegment;
         for(int k = endIdx - 1; k >= 0; k--) {
            LineRef kRef = allLines.get(k);
            if(kRef.scope == null || !scope.equals(kRef.scope)) break;
            AsmLine line = kRef.line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
               continue;
            }
            if(line instanceof AsmInstruction inst) {
               if(!isScopeIndependentInstruction(inst, declaredInScope, globalDeclaredSymbols)) {
                  break;
               }
               reversedRefs.add(kRef);
               totalBytes += inst.getLineBytes();
               firstInstGlobalIdx = k;
               continue;
            }
            // Stop at AsmLabel, AsmInlineKickAsm, AsmDataNumeric, AsmSegment, AsmScopeBegin, etc.
            break;
         }

         if(reversedRefs.size() >= 2 && totalBytes >= 4) {
            Collections.reverse(reversedRefs);
            String preLabel = findImmediatePrecedingNamedLabel(allLines, firstInstGlobalIdx);
            TailSeq epilogue = new TailSeq(scope, seg, reversedRefs, Collections.emptyList(), preLabel, totalBytes);
            EpilogueKey key = new EpilogueKey(seg, epilogue.secondLastInst(), epilogue.lastInst());
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(epilogue);
         }
      }

      boolean modified = false;
      for(List<TailSeq> group : buckets.values()) {
         if(group.size() < 2) continue;

         // Sort longest first so a shorter epilogue can jump into a suffix of a longer canonical epilogue
         group.sort((a, b) -> Integer.compare(b.totalBytes, a.totalBytes));
         List<TailSeq> canonicals = new ArrayList<>();
         Map<TailSeq, Map<Integer, String>> existingEpilogueLabelAtOffset = new IdentityHashMap<>();

         for(TailSeq candidate : group) {
            TailSeq matchedCanon = null;
            int bestMatchCount = 0;
            int bestMatchBytes = 0;

            for(TailSeq canon : canonicals) {
               if(canon.scope.equals(candidate.scope)) continue;
               if(!isScopeDirty(candidate.scope) && !isScopeDirty(canon.scope)) continue;
               int mc = commonSuffixInstructionCount(canon.instRefs, candidate.instRefs);
               if(mc < 2) continue;
               int mb = suffixByteSize(canon.instRefs, mc);
               if(mb < 4) continue;
               if(mb > bestMatchBytes || (mb == bestMatchBytes && mc > bestMatchCount)) {
                  bestMatchBytes = mb;
                  bestMatchCount = mc;
                  matchedCanon = canon;
               }
            }

            if(matchedCanon != null) {
               int canonStartOffset = matchedCanon.instRefs.size() - bestMatchCount;
               Map<Integer, String> offsetMap = existingEpilogueLabelAtOffset.computeIfAbsent(matchedCanon, k -> new HashMap<>());
               String qualTargetLabel = offsetMap.get(canonStartOffset);
               if(qualTargetLabel == null) {
                  String localEpLabel = null;
                  if(canonStartOffset == 0 && matchedCanon.precedingNamedLabel != null) {
                     // Check if preceding label in matchedCanon is already an __epilogue_* nooptimize label
                     LineRef firstCanonRef = matchedCanon.instRefs.get(0);
                     List<AsmLine> chunkLines = firstCanonRef.chunk.getLines();
                     int pos = chunkLines.indexOf(firstCanonRef.line);
                     if(pos > 0 && chunkLines.get(pos - 1) instanceof AsmLabel prevLbl && prevLbl.isNoOptimize()
                           && matchedCanon.precedingNamedLabel.equals(prevLbl.getLabel())) {
                        localEpLabel = prevLbl.getLabel();
                     }
                  }
                  if(localEpLabel == null) {
                     localEpLabel = nextUniqueEpilogueLabel(existingScopedLabels, matchedCanon.scope);
                     LineRef canonStartRef = matchedCanon.instRefs.get(canonStartOffset);
                     AsmLabel lbl = new AsmLabel(localEpLabel);
                     lbl.setNoOptimize(true);
                     canonStartRef.chunk.addLineBefore(canonStartRef.line, lbl);
                     markScopeDirty(matchedCanon.scope);
                  }
                  qualTargetLabel = matchedCanon.scope + "." + localEpLabel;
                  offsetMap.put(canonStartOffset, qualTargetLabel);
               }

               int dupStartOffset = candidate.instRefs.size() - bestMatchCount;
               LineRef firstDupRef = candidate.instRefs.get(dupStartOffset);
               AsmInstruction firstDupInst = (AsmInstruction) firstDupRef.line;

               getLog().append("Pass5TailMerging (cross-procedure " + candidate.scope + " -> " + matchedCanon.scope
                     + " in segment " + candidate.segment + "): merging " + bestMatchCount
                     + " instructions (" + bestMatchBytes + " bytes) -> jmp " + qualTargetLabel);

               firstDupInst.setCpuOpcode(jmpAbsOpcode);
               firstDupInst.setOperand1(qualTargetLabel);
               firstDupInst.setOperand2(null);
               firstDupInst.setOperand3(null);

               for(int k = dupStartOffset + 1; k < candidate.instRefs.size(); k++) {
                  LineRef remRef = candidate.instRefs.get(k);
                  remRef.chunk.getLines().remove(remRef.line);
               }

               markScopeDirty(candidate.scope);
               modified = true;
            } else {
               canonicals.add(candidate);
            }
         }
      }
      return modified;
   }
}
