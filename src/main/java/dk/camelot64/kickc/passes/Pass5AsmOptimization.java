package dk.camelot64.kickc.passes;

import dk.camelot64.cpufamily6502.CpuAddressingMode;
import dk.camelot64.cpufamily6502.CpuOpcode;
import dk.camelot64.kickc.CompileLog;
import dk.camelot64.kickc.asm.*;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.symbols.Procedure;
import dk.camelot64.kickc.model.symbols.Scope;
import dk.camelot64.kickc.model.symbols.Symbol;
import dk.camelot64.kickc.model.symbols.Variable;
import dk.camelot64.kickc.model.types.SymbolType;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Optimization performed on Assembler Code (Asm Code).
 * Optimizations are performed repeatedly until none of them yield any result
 **/
public abstract class Pass5AsmOptimization {

   // =========================================================================
   // Bitmask constants for MOS 6502/6510 status flags {N, Z, C, V}
   // =========================================================================
   public static final int FLAG_NONE = 0;
   public static final int FLAG_N    = 1 << 0;
   public static final int FLAG_Z    = 1 << 1;
   public static final int FLAG_C    = 1 << 2;
   public static final int FLAG_V    = 1 << 3;
   public static final int FLAG_NZ   = FLAG_N | FLAG_Z;
   public static final int FLAG_NZC  = FLAG_N | FLAG_Z | FLAG_C;
   public static final int FLAG_NZV  = FLAG_N | FLAG_Z | FLAG_V;
   public static final int FLAG_ALL  = FLAG_N | FLAG_Z | FLAG_C | FLAG_V;

   private static final AsmComment TOMBSTONE_COMMENT = new AsmComment("", false);

   /**
    * PF-5: Removes the line at idx from its owning AsmChunk and replaces the entry in allLines
    * with a zero-effect AsmComment tombstone so single-pass peepholes can continue without O(K*L) restarts.
    */
   public static void tombstoneLine(List<LineRef> allLines, int idx) {
      if(idx < 0 || idx >= allLines.size()) return;
      LineRef ref = allLines.get(idx);
      if(ref != null && ref.line != TOMBSTONE_COMMENT) {
         ref.chunk.getLines().remove(ref.line);
         allLines.set(idx, new LineRef(ref.index, ref.chunk, ref.indexInChunk, TOMBSTONE_COMMENT, ref.scope));
      }
   }

   public static class LineRef {
      public final int index;
      public final AsmChunk chunk;
      public final int indexInChunk;
      public final AsmLine line;
      public final String scope;

      public LineRef(AsmChunk chunk, AsmLine line) {
         this(-1, chunk, -1, line, "");
      }

      public LineRef(int index, AsmChunk chunk, AsmLine line, String scope) {
         this(index, chunk, -1, line, scope);
      }

      public LineRef(AsmChunk chunk, int indexInChunk, AsmLine line, String scope) {
         this(-1, chunk, indexInChunk, line, scope);
      }

      public LineRef(int index, AsmChunk chunk, int indexInChunk, AsmLine line, String scope) {
         this.index = index;
         this.chunk = chunk;
         this.indexInChunk = indexInChunk;
         this.line = line;
         this.scope = scope != null ? scope : "";
      }
   }

   /**
    * Resolves .label alias chains and folds numeric offsets for ASM operands.
    */
   public static class CanonicalResolver {
      private final CanonicalResolver parent;
      private final Map<String, String> aliasMap = new HashMap<>();

      public CanonicalResolver() {
         this.parent = null;
      }

      public CanonicalResolver(CanonicalResolver parent) {
         this.parent = parent;
      }

      public CanonicalResolver(Map<String, String> initialAliases) {
         this.parent = null;
         if(initialAliases != null) {
            aliasMap.putAll(initialAliases);
         }
      }

      private String lookupExact(String key) {
         CanonicalResolver cur = this;
         while(cur != null) {
            String val = cur.aliasMap.get(key);
            if(val != null) return val;
            cur = cur.parent;
         }
         return null;
      }

      public void addAlias(String name, String value) {
         if(name == null || value == null) return;
         String n = name.trim();
         String v = value.trim();
         if(v.startsWith(".z ")) v = v.substring(3).trim();
         aliasMap.put(n, v);
         int dotIdx = n.lastIndexOf('.');
         if(dotIdx != -1) {
            aliasMap.put(n.substring(dotIdx + 1), v);
         }
      }

      public String resolve(String operand) {
         if(operand == null) return "";
         String op = operand.trim();
         if(op.startsWith(".z ")) op = op.substring(3).trim();

         // Extract base and initial offset
         String base = op;
         int offset = 0;
         int plusIdx = base.indexOf('+');
         int minusIdx = base.indexOf('-');
         if(plusIdx != -1) {
            try {
               offset = Integer.parseInt(base.substring(plusIdx + 1).trim());
               base = base.substring(0, plusIdx).trim();
            } catch(Exception ignored) {}
         } else if(minusIdx != -1) {
            try {
               offset = -Integer.parseInt(base.substring(minusIdx + 1).trim());
               base = base.substring(0, minusIdx).trim();
            } catch(Exception ignored) {}
         }

         // Follow alias chains
         Set<String> visited = new HashSet<>();
         visited.add(base);
         for(int i = 0; i < 20; i++) {
            String next = lookupExact(base);
            if(next == null) {
               int dotIdx = base.lastIndexOf('.');
               if(dotIdx != -1) {
                  next = lookupExact(base.substring(dotIdx + 1));
               }
            }
            if(next == null) break;
            if(next.startsWith(".z ")) next = next.substring(3).trim();

            int nPlus = next.indexOf('+');
            int nMinus = next.indexOf('-');
            if(nPlus != -1) {
               try {
                  offset += Integer.parseInt(next.substring(nPlus + 1).trim());
                  base = next.substring(0, nPlus).trim();
               } catch(Exception e) {
                  base = next;
               }
            } else if(nMinus != -1) {
               try {
                  offset -= Integer.parseInt(next.substring(nMinus + 1).trim());
                  base = next.substring(0, nMinus).trim();
               } catch(Exception e) {
                  base = next;
               }
            } else {
               base = next;
            }

            if(!visited.add(base)) break; // cycle protection
         }

         // If base is a numeric hardware address, fold the offset into the address
         Integer addr = parseHexOrDecAddress(base);
         if(addr != null) {
            int finalAddr = addr + offset;
            if(finalAddr >= 0 && finalAddr <= 0xff) {
               return String.format("$%02x", finalAddr);
            } else {
               return String.format("$%04x", finalAddr & 0xffff);
            }
         }

         if(offset != 0) {
            return base + (offset > 0 ? "+" + offset : "" + offset);
         }
         return base;
      }
   }

   private Program program;
   private Pass5RoundContext roundContext;

   public Pass5AsmOptimization(Program program) {
      this.program = program;
   }

   public void setRoundContext(Pass5RoundContext ctx) {
      this.roundContext = ctx;
   }

   public Pass5RoundContext getRoundContext() {
      return this.roundContext;
   }

   protected List<LineRef> getSharedAllLines() {
      if(roundContext != null) {
         return roundContext.getAllLines();
      }
      return flattenLines(getAsmProgram());
   }

   protected Map<String, CanonicalResolver> getSharedScopeResolvers(List<LineRef> allLines) {
      if(roundContext != null) {
         return roundContext.getScopeResolvers();
      }
      return buildScopeResolvers(allLines);
   }

   protected Map<String, List<LineRef>> getSharedScopeLinesMap(List<LineRef> allLines) {
      if(roundContext != null) {
         return roundContext.getScopeLinesMap();
      }
      Map<String, List<LineRef>> map = new LinkedHashMap<>();
      for(LineRef ref : allLines) {
         String scope = ref.scope != null ? ref.scope : "";
         map.computeIfAbsent(scope, k -> new ArrayList<>()).add(ref);
      }
      return map;
   }

   protected boolean isScopeDirty(String scope) {
      return roundContext == null || roundContext.isScopeDirty(scope);
   }

   protected void markScopeDirty(String scope) {
      if(roundContext != null) {
         roundContext.markScopeDirty(scope);
      }
   }

   protected void markAllScopesDirty() {
      if(roundContext != null) {
         roundContext.markAllScopesDirty();
      }
   }

   protected void invalidateRoundStructure() {
      if(roundContext != null) {
         roundContext.invalidateStructure();
      }
   }

   protected void invalidateRoundLabels() {
      if(roundContext != null) {
         roundContext.invalidateLabels();
      }
   }

   /**
    * Attempt to perform optimization.
    *
    * @return true if an optimization was performed. false if no optimization was possible.
    */
   public abstract boolean optimize();

   public Program getProgram() {
      return program;
   }

   public AsmProgram getAsmProgram() {
      return program.getAsm();
   }

   public CompileLog getLog() {
      return program.getLog();
   }

   public void remove(List<AsmLine> remove) {
      if(remove == null || remove.isEmpty()) {
         return;
      }
      Set<AsmLine> removeSet = (remove instanceof Set) ? (Set<AsmLine>) remove : new HashSet<>(remove);
      Collection<AsmChunk> chunks = getAsmProgram().getChunks();
      for(AsmChunk chunk : chunks) {
         for(Iterator<AsmLine> iterator = chunk.getLines().iterator(); iterator.hasNext(); ) {
            AsmLine line = iterator.next();
            if(removeSet.contains(line)) {
               getLog().append("Removing instruction " + line.getAsm());
               iterator.remove();
            }
         }
      }
   }

   public boolean remove(ListIterator<AsmLine> lineIt) {
      getLog().append("Removing instruction " + lineIt.previous());
      lineIt.remove();
      return true;
   }

   // =========================================================================
   // Shared Flattening, Lookahead & Operand Helpers (TD-3)
   // =========================================================================

   /**
    * Flattens all lines across all chunks of an AsmProgram, recording global index,
    * chunk, index within chunk, line, and active scope label.
    */
   public static List<LineRef> flattenLines(AsmProgram asmProgram) {
      List<LineRef> allLines = new ArrayList<>();
      String currentScope = "";
      int globalIdx = 0;
      for(AsmChunk chunk : asmProgram.getChunks()) {
         List<AsmLine> chunkLines = chunk.getLines();
         for(int i = 0; i < chunkLines.size(); i++) {
            AsmLine line = chunkLines.get(i);
            if(line instanceof AsmScopeBegin) {
               currentScope = ((AsmScopeBegin) line).getLabel();
            }
            allLines.add(new LineRef(globalIdx++, chunk, i, line, currentScope));
            if(line instanceof AsmScopeEnd) {
               currentScope = "";
            }
         }
      }
      return allLines;
   }

   /**
    * Builds a CanonicalResolver per scope (including global aliases) from flattened lines.
    */
   public static Map<String, CanonicalResolver> buildScopeResolvers(List<LineRef> allLines) {
      Map<String, String> globalAliases = new HashMap<>();
      Map<String, Map<String, String>> perScopeAliases = new HashMap<>();

      for(LineRef ref : allLines) {
         if(ref.line instanceof AsmLabelDecl) {
            AsmLabelDecl decl = (AsmLabelDecl) ref.line;
            String rawName = decl.getName();
            String rawVal = decl.getValue();
            if(ref.scope != null && !ref.scope.isEmpty()) {
               perScopeAliases.computeIfAbsent(ref.scope, k -> new HashMap<>()).put(rawName, rawVal);
               String qualName = rawName.contains(".") ? rawName : (ref.scope + "." + rawName);
               String qualVal = (rawVal.contains(".") || rawVal.startsWith("$") || rawVal.startsWith("#"))
                  ? rawVal : (ref.scope + "." + rawVal);
               globalAliases.put(qualName, qualVal);
            } else {
               globalAliases.put(rawName, rawVal);
            }
         }
      }

      Map<String, CanonicalResolver> resolvers = new HashMap<>();
      CanonicalResolver rootResolver = new CanonicalResolver();
      for(Map.Entry<String, String> e : globalAliases.entrySet()) {
         rootResolver.addAlias(e.getKey(), e.getValue());
      }
      resolvers.put("", rootResolver);

      for(Map.Entry<String, Map<String, String>> entry : perScopeAliases.entrySet()) {
         CanonicalResolver sr = new CanonicalResolver(rootResolver);
         for(Map.Entry<String, String> le : entry.getValue().entrySet()) {
            sr.addAlias(le.getKey(), le.getValue());
         }
         resolvers.put(entry.getKey(), sr);
      }
      return resolvers;
   }

   /**
    * Finds the next executable instruction at or after start within the same basic block
    * (skipping comments, constants, and label declarations).
    */
   public static int findNextInstruction(List<LineRef> allLines, int start) {
      for(int j = start; j < allLines.size(); j++) {
         LineRef candidate = allLines.get(j);
         if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
            continue;
         }
         if(candidate.line instanceof AsmInstruction) {
            return j;
         }
         break;
      }
      return -1;
   }

   /**
    * Finds the previous executable instruction at or before start within the same basic block
    * (skipping comments, constants, and label declarations).
    */
   public static int findPrevInstruction(List<LineRef> allLines, int start) {
      for(int j = start; j >= 0; j--) {
         LineRef candidate = allLines.get(j);
         if(candidate.line instanceof AsmComment || candidate.line instanceof AsmConstant || candidate.line instanceof AsmLabelDecl) {
            continue;
         }
         if(candidate.line instanceof AsmInstruction) {
            return j;
         }
         break;
      }
      return -1;
   }

   /**
    * Finds the index of the given label (supports anonymous !+ / !- multi-labels as well as named labels),
    * strictly respecting procedure scope boundaries.
    */
   public static int findLabelIndex(List<LineRef> allLines, String labelName, int fromIdx) {
      String currentScope = (fromIdx >= 0 && fromIdx < allLines.size()) ? allLines.get(fromIdx).scope : "";
      return findLabelIndex(allLines, labelName, fromIdx, currentScope);
   }

   private static final class LabelIndex {
      final WeakReference<List<LineRef>> listRef;
      final int size;
      final Map<String, Integer> scopedLabels;
      final Map<String, Integer> globalLabels;

      LabelIndex(List<LineRef> allLines) {
         this.listRef = new WeakReference<>(allLines);
         this.size = allLines.size();
         this.scopedLabels = new HashMap<>();
         this.globalLabels = new HashMap<>();
         for(int i = 0; i < allLines.size(); i++) {
            LineRef ref = allLines.get(i);
            if(ref.line instanceof AsmLabel) {
               String l = ((AsmLabel) ref.line).getLabel();
               if(l != null && !l.startsWith("!")) {
                  String scope = ref.scope != null ? ref.scope : "";
                  scopedLabels.putIfAbsent(scope + '\0' + l, i);
                  if(scope.isEmpty()) {
                     globalLabels.putIfAbsent(l, i);
                  }
               }
            }
         }
      }
   }

   private static final ThreadLocal<LabelIndex> LABEL_INDEX_CACHE = new ThreadLocal<>();

   private static LabelIndex getLabelIndex(List<LineRef> allLines) {
      LabelIndex index = LABEL_INDEX_CACHE.get();
      if(index == null || index.listRef.get() != allLines || index.size != allLines.size()) {
         index = new LabelIndex(allLines);
         LABEL_INDEX_CACHE.set(index);
      }
      return index;
   }

   private static int lookupLabelFromIndex(List<LineRef> allLines, String key, boolean useGlobalMap) {
      LabelIndex index = getLabelIndex(allLines);
      Map<String, Integer> map = useGlobalMap ? index.globalLabels : index.scopedLabels;
      Integer idx = map.get(key);
      if(idx != null) {
         if(allLines.get(idx).line instanceof AsmLabel) {
            return idx;
         }
         index = new LabelIndex(allLines);
         LABEL_INDEX_CACHE.set(index);
         map = useGlobalMap ? index.globalLabels : index.scopedLabels;
         idx = map.get(key);
         if(idx != null && allLines.get(idx).line instanceof AsmLabel) {
            return idx;
         }
      }
      return -1;
   }

   /**
    * Finds the index of the given label within currentScope (supports anonymous !+ / !- / !++ / !--
    * multi-labels as well as scoped and global named labels).
    */
   public static int findLabelIndex(List<LineRef> allLines, String labelName, int fromIdx, String currentScope) {
      if(labelName == null) return -1;
      String clean = labelName.trim();
      if(clean.isEmpty()) return -1;
      String targetScope = (currentScope != null) ? currentScope : "";

      if(clean.startsWith("!")) {
         boolean forward;
         int hops = 0;
         int idx = clean.length() - 1;
         if(clean.endsWith("+")) {
            forward = true;
            while(idx >= 0 && clean.charAt(idx) == '+') {
               hops++;
               idx--;
            }
         } else if(clean.endsWith("-")) {
            forward = false;
            while(idx >= 0 && clean.charAt(idx) == '-') {
               hops++;
               idx--;
            }
         } else {
            return -1;
         }
         if(hops <= 0) return -1;
         String base = clean.substring(0, idx + 1);

         if(forward) {
            for(int i = fromIdx + 1; i < allLines.size(); i++) {
               LineRef ref = allLines.get(i);
               if(ref.line instanceof AsmScopeBegin || ref.line instanceof AsmScopeEnd || !Objects.equals(targetScope, ref.scope)) {
                  break;
               }
               if(ref.line instanceof AsmLabel) {
                  String l = ((AsmLabel) ref.line).getLabel();
                  if(base.equals(l)) {
                     hops--;
                     if(hops == 0) return i;
                  }
               }
            }
         } else {
            for(int i = fromIdx - 1; i >= 0; i--) {
               LineRef ref = allLines.get(i);
               if(ref.line instanceof AsmScopeBegin || ref.line instanceof AsmScopeEnd || !Objects.equals(targetScope, ref.scope)) {
                  break;
               }
               if(ref.line instanceof AsmLabel) {
                  String l = ((AsmLabel) ref.line).getLabel();
                  if(base.equals(l)) {
                     hops--;
                     if(hops == 0) return i;
                  }
               }
            }
         }
         return -1;
      }

      // Qualified label (scope.label)
      int dotIdx = clean.indexOf('.');
      if(dotIdx != -1) {
         String scopePart = clean.substring(0, dotIdx);
         String labelPart = clean.substring(dotIdx + 1);
         return lookupLabelFromIndex(allLines, scopePart + '\0' + labelPart, false);
      }

      // Named label strictly within the active procedure scope:
      int idx = lookupLabelFromIndex(allLines, targetScope + '\0' + clean, false);
      if(idx != -1) {
         return idx;
      }

      // Fallback only for top-level global labels (never match local labels in other procedures)
      if(!targetScope.isEmpty()) {
         return lookupLabelFromIndex(allLines, clean, true);
      }
      return -1;
   }

   /**
    * Extracts the base variable/symbol name from an operand (strips .z prefix and +/- offset).
    */
   public static String getBaseVar(String op) {
      if(op == null) return "";
      String s = op.trim();
      if(s.startsWith(".z ")) s = s.substring(3).trim();
      int plusIdx = s.indexOf('+');
      int minusIdx = s.indexOf('-');
      int splitIdx = -1;
      if(plusIdx != -1 && minusIdx != -1) splitIdx = Math.min(plusIdx, minusIdx);
      else if(plusIdx != -1) splitIdx = plusIdx;
      else if(minusIdx != -1) splitIdx = minusIdx;
      if(splitIdx != -1) return s.substring(0, splitIdx).trim();
      return s;
   }

   /**
    * Parses an integer +N offset from an operand (returns 0 if no +, -1 if unparseable).
    */
   public static int getOffset(String op) {
      if(op == null) return 0;
      int idx = op.indexOf('+');
      if(idx != -1) {
         try {
            return Integer.parseInt(op.substring(idx + 1).trim());
         } catch(Exception e) {
            return -1;
         }
      }
      return 0;
   }

   /**
    * Checks if an ASM operand string evaluates to integer 0.
    */
   public static boolean isZeroOperand(String op) {
      if(op == null) return false;
      String trimmed = op.trim();
      if(trimmed.startsWith("#")) trimmed = trimmed.substring(1).trim();
      if(trimmed.startsWith("<") || trimmed.startsWith(">")) {
         trimmed = trimmed.substring(1).trim();
      }
      if(trimmed.equals("0") || trimmed.equals("$0") || trimmed.equals("$00") || trimmed.equals("0x0") || trimmed.equals("0x00")) {
         return true;
      }
      try {
         if(trimmed.startsWith("$")) {
            return Integer.parseInt(trimmed.substring(1), 16) == 0;
         } else if(trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            return Integer.parseInt(trimmed.substring(2), 16) == 0;
         } else {
            return Integer.parseInt(trimmed) == 0;
         }
      } catch(NumberFormatException e) {
         return false;
      }
   }

   /**
    * Checks if an ASM operand string evaluates to integer 1.
    */
   public static boolean isOneOperand(String op) {
      if(op == null) return false;
      String trimmed = op.trim();
      if(trimmed.contains(">") || trimmed.contains("<")) return false;
      Integer val = AsmProgramStaticRegisterValues.getImmValue(op);
      return val != null && val == 1;
   }

   /**
    * Checks if an operand matches a specific zero-page hex address (e.g. "$fd", "$fe", "$ff").
    */
   public static boolean isZpAddressOperand(String op, String hexAddr) {
      if(op == null || hexAddr == null) return false;
      String t = op.trim().toLowerCase();
      if(t.startsWith(".z ")) t = t.substring(3).trim();
      if(t.startsWith("(") && t.endsWith(")")) {
         t = t.substring(1, t.length() - 1).trim();
      }
      String normHex = hexAddr.trim().toLowerCase();
      if(normHex.startsWith("$")) normHex = normHex.substring(1);
      return t.equals("$" + normHex) || t.equals("$$" + normHex) || t.equals("$00" + normHex) || t.equals("0x" + normHex);
   }

   /**
    * Parses a hex ($xxxx, 0xxxxx) or decimal address string. Returns null if not numeric.
    */
   public static Integer parseHexOrDecAddress(String s) {
      if(s == null) return null;
      String t = s.trim().toLowerCase();
      if(t.isEmpty()) return null;
      try {
         if(t.startsWith("$$")) {
            return Integer.parseInt(t.substring(2), 16);
         } else if(t.startsWith("$")) {
            return Integer.parseInt(t.substring(1), 16);
         } else if(t.startsWith("0x")) {
            return Integer.parseInt(t.substring(2), 16);
         } else {
            for(int i = 0; i < t.length(); i++) {
               if(!Character.isDigit(t.charAt(i))) return null;
            }
            return Integer.parseInt(t);
         }
      } catch(NumberFormatException e) {
         return null;
      }
   }

   // =========================================================================
   // Volatile & Hardware I/O Guard (SSA-01)
   // =========================================================================

   /**
    * Checks if a raw or canonical address string refers to a hardware I/O register:
    * - 6510 CPU I/O port ($0000-$0001)
    * - Memory-mapped I/O area ($D000-$DFFF)
    * - Known hardware register naming hints
    */
   public static boolean isHardwareAddress(String op) {
      if(op == null) return false;
      String t = op.trim().toLowerCase();
      if(t.startsWith(".z ")) t = t.substring(3).trim();
      if(t.startsWith("#")) return false;

      Integer addr = parseHexOrDecAddress(t);
      if(addr != null) {
         int a = addr & 0xffff;
         if((a >= 0x0000 && a <= 0x0001) || (a >= 0xd000 && a <= 0xdfff)) {
            return true;
         }
      }
      // Also check base part before +/- offset if it's a numeric address + offset
      String base = getBaseVar(t);
      if(!base.equals(t)) {
         Integer baseAddr = parseHexOrDecAddress(base);
         if(baseAddr != null) {
            int offset = getOffset(t);
            int a = (baseAddr + Math.max(0, offset)) & 0xffff;
            if((a >= 0x0000 && a <= 0x0001) || (a >= 0xd000 && a <= 0xdfff)) {
               return true;
            }
         }
      }
      if(t.contains("bank_reg") || t.contains("vicii") || t.contains("sid") || t.contains("cia") || t.contains("colorram")) {
         return true;
      }
      return false;
   }

   /**
    * Immutable O(1) lookup index for resolving ASM operand names to ProgramScope Variables
    * during Pass 5 without repeated linear getAllVars() scans or regex splitting.
    */
   public static final class AsmVarLookupIndex {
      private final Map<String, Variable> qualifiedMap;
      private final Map<String, Variable> rootMap;
      private final Map<String, Map<String, Variable>> scopedMap;

      private AsmVarLookupIndex(
            Map<String, Variable> qualifiedMap,
            Map<String, Variable> rootMap,
            Map<String, Map<String, Variable>> scopedMap) {
         this.qualifiedMap = qualifiedMap;
         this.rootMap = rootMap;
         this.scopedMap = scopedMap;
      }

      public static AsmVarLookupIndex build(dk.camelot64.kickc.model.symbols.ProgramScope programScope) {
         Map<String, Variable> qualifiedMap = new HashMap<>();
         Map<String, Variable> rootMap = new HashMap<>();
         Map<String, Map<String, Variable>> scopedMap = new HashMap<>();

         for(Variable v : programScope.getAllVars(true)) {
            if(v == null) continue;
            String localName = v.getLocalName();
            String asmName = v.getAsmName();
            String fullName = v.getFullName();
            if(fullName != null && !fullName.isEmpty()) {
               qualifiedMap.putIfAbsent(fullName, v);
            }
            Scope sc = v.getScope();
            if(sc == null || sc == programScope) {
               if(localName != null && !localName.isEmpty()) {
                  rootMap.putIfAbsent(localName, v);
               }
               if(asmName != null && !asmName.isEmpty()) {
                  rootMap.putIfAbsent(asmName, v);
               }
            } else {
               String scLocal = sc.getLocalName();
               String scFull = sc.getFullName();
               if(scLocal != null && !scLocal.isEmpty()) {
                  Map<String, Variable> sm = scopedMap.computeIfAbsent(scLocal, k -> new HashMap<>());
                  if(localName != null && !localName.isEmpty()) {
                     sm.putIfAbsent(localName, v);
                     qualifiedMap.putIfAbsent(scLocal + "." + localName, v);
                  }
                  if(asmName != null && !asmName.isEmpty()) {
                     sm.putIfAbsent(asmName, v);
                     qualifiedMap.putIfAbsent(scLocal + "." + asmName, v);
                  }
               }
               if(scFull != null && !scFull.isEmpty() && !scFull.equals(scLocal)) {
                  Map<String, Variable> sm = scopedMap.computeIfAbsent(scFull, k -> new HashMap<>());
                  if(localName != null && !localName.isEmpty()) {
                     sm.putIfAbsent(localName, v);
                     qualifiedMap.putIfAbsent(scFull + "." + localName, v);
                  }
                  if(asmName != null && !asmName.isEmpty()) {
                     sm.putIfAbsent(asmName, v);
                     qualifiedMap.putIfAbsent(scFull + "." + asmName, v);
                  }
               }
            }
         }
         return new AsmVarLookupIndex(qualifiedMap, rootMap, scopedMap);
      }

      public Variable find(String base, String currentScope) {
         if(base.indexOf('.') != -1 || base.indexOf(':') != -1) {
            Variable v = qualifiedMap.get(base);
            if(v != null) return v;
         }
         if(currentScope != null && !currentScope.isEmpty()) {
            Map<String, Variable> sm = scopedMap.get(currentScope);
            if(sm != null) {
               Variable v = sm.get(base);
               if(v != null) return v;
            }
         }
         Variable rootVar = rootMap.get(base);
         if(rootVar != null) return rootVar;
         return qualifiedMap.get(base);
      }
   }

   /**
    * Looks up a Variable symbol in the ProgramScope in O(1) via the cached AsmVarLookupIndex
    * (checking qualified names, currentScope locals, then global root scope).
    */
   public static Variable findVariableInScope(String name, String currentScope, Program program) {
      if(name == null || name.isEmpty() || program == null || program.getScope() == null) return null;
      String clean = name.trim();
      if(clean.startsWith(".z ")) clean = clean.substring(3).trim();
      // Strip indexing suffix like ",x" or ",y"
      int commaIdx = clean.indexOf(',');
      if(commaIdx != -1) clean = clean.substring(0, commaIdx).trim();
      // Strip parentheses
      if(clean.startsWith("(") && clean.endsWith(")")) {
         clean = clean.substring(1, clean.length() - 1).trim();
      }
      String base = getBaseVar(clean);
      if(base.isEmpty()) return null;
      char first = base.charAt(0);
      if(first == '$' || first == '#' || first == '%' || (first >= '0' && first <= '9')) return null;

      AsmVarLookupIndex idx = program.getAsmVarLookupIndex();
      return idx != null ? idx.find(base, currentScope) : null;
   }

   public static boolean isIndirectAddressingMode(CpuAddressingMode mode) {
      if(mode == null) return false;
      return mode == CpuAddressingMode.IZY || mode == CpuAddressingMode.IZX ||
             mode == CpuAddressingMode.IZZ || mode == CpuAddressingMode.IND ||
             mode == CpuAddressingMode.INZ || mode == CpuAddressingMode.IAX ||
             mode == CpuAddressingMode.LIN || mode == CpuAddressingMode.LIZ;
   }

   /**
    * Central guard (SSA-01): Returns true if the given ASM instruction accesses a volatile variable,
    * dereferences a pointer-to-volatile (including mutable volatile T *p via indirect addressing),
    * or accesses a hardware I/O address ($0000-$0001, $D000-$DFFF), after resolving .label aliases.
    */
   public static boolean isVolatileOrHardwareOperand(AsmInstruction inst, String currentScope, CanonicalResolver resolver, Program program) {
      if(inst == null) return false;
      CpuAddressingMode mode = inst.getCpuOpcode() != null ? inst.getCpuOpcode().getAddressingMode() : null;
      return isVolatileOrHardwareOperand(inst.getOperand1(), mode, currentScope, resolver, program);
   }

   /**
    * Central guard (SSA-01): Returns true if the given ASM operand accesses a volatile variable
    * or a hardware I/O address ($0000-$0001, $D000-$DFFF), after resolving .label aliases.
    */
   public static boolean isVolatileOrHardwareOperand(String op, String currentScope, CanonicalResolver resolver, Program program) {
      return isVolatileOrHardwareOperand(op, null, currentScope, resolver, program);
   }

   public static boolean isVolatileOrHardwareOperand(String op, CpuAddressingMode mode, String currentScope, CanonicalResolver resolver, Program program) {
      if(op == null) return false;
      String trimmed = op.trim();
      if(trimmed.isEmpty()) return false;
      if(trimmed.startsWith(".z ")) trimmed = trimmed.substring(3).trim();
      if(trimmed.startsWith("#") || mode == CpuAddressingMode.IMM || mode == CpuAddressingMode.IMW) return false;

      boolean isIndirect = isIndirectAddressingMode(mode) || trimmed.contains("(") || trimmed.contains(")");

      // 1. Check raw operand for hardware address
      if(isHardwareAddress(trimmed)) {
         return true;
      }

      // 2. Check canonical resolved operand for hardware address
      String canonical = (resolver != null) ? resolver.resolve(trimmed) : trimmed;
      if(!canonical.equals(trimmed) && isHardwareAddress(canonical)) {
         return true;
      }

      // 3. Check KickC symbol table for volatile variables (both raw and canonical names)
      if(program != null) {
         Variable rawVar = findVariableInScope(trimmed, currentScope, program);
         if(rawVar != null && (rawVar.isVolatile() || (isIndirect && rawVar.isToVolatile()) || (rawVar.isKindConstant() && rawVar.isToVolatile()))) {
            return true;
         }
         if(!canonical.equals(trimmed)) {
            Variable canonVar = findVariableInScope(canonical, currentScope, program);
            if(canonVar != null && (canonVar.isVolatile() || (isIndirect && canonVar.isToVolatile()) || (canonVar.isKindConstant() && canonVar.isToVolatile()))) {
               return true;
            }
         }
      }

      return false;
   }

   /**
    * Checks if a pointer pair (loOp, hiOp) loaded into indirect ZP ($fe/$ff) points to volatile
    * memory or a hardware I/O address ($0000-$0001, $D000-$DFFF).
    */
   public static boolean isPointerToVolatileOrHardware(String loOp, String hiOp, String currentScope, CanonicalResolver resolver, Program program) {
      if(loOp == null && hiOp == null) return true; // unknown pointer -> conservative
      // Check if either operand is itself volatile or a pointer-to-volatile variable
      if(program != null) {
         for(String op : new String[]{loOp, hiOp}) {
            if(op == null) continue;
            Variable var = findVariableInScope(op, currentScope, program);
            if(var != null && (var.isVolatile() || var.isToVolatile())) {
               return true;
            }
            if(resolver != null) {
               String canon = resolver.resolve(op);
               if(!canon.equals(op)) {
                  Variable canonVar = findVariableInScope(canon, currentScope, program);
                  if(canonVar != null && (canonVar.isVolatile() || canonVar.isToVolatile())) {
                     return true;
                  }
               }
            }
         }
      }
      // Check if loOp/hiOp are immediate bytes forming a hardware address ($0000-$0001 or $D000-$DFFF)
      if(hiOp != null) {
         String hiTrim = hiOp.trim();
         if(hiTrim.startsWith("#")) {
            Integer hiVal = AsmProgramStaticRegisterValues.getImmValue(hiTrim);
            if(hiVal != null) {
               int hiByte = hiVal & 0xff;
               if(hiByte >= 0xd0 && hiByte <= 0xdf) {
                  return true;
               }
               if(hiByte == 0x00 && loOp != null && loOp.trim().startsWith("#")) {
                  Integer loVal = AsmProgramStaticRegisterValues.getImmValue(loOp.trim());
                  if(loVal != null && (loVal & 0xff) <= 1) {
                     return true;
                  }
               }
            }
         }
         // Check #<SYM / #>SYM referencing a hardware address
         if(hiTrim.startsWith("#>")) {
            String sym = hiTrim.substring(2).trim();
            if(isVolatileOrHardwareOperand(sym, currentScope, resolver, program)) {
               return true;
            }
         }
      }
      if(loOp != null && loOp.trim().startsWith("#<")) {
         String sym = loOp.trim().substring(2).trim();
         if(isVolatileOrHardwareOperand(sym, currentScope, resolver, program)) {
            return true;
         }
      }
      return false;
   }

   // =========================================================================
   // Register Liveness Helper (TD-4)
   // =========================================================================

   /**
    * Checks whether register reg ("A", "X", or "Y") is guaranteed dead (overwritten before read)
    * along the execution path starting at startIdx.
    */
   public static boolean isRegDeadAlongPath(String reg, List<LineRef> allLines, int startIdx, Set<String> visitedLabels, String currentScope, Program program) {
      for(int m = startIdx; m < allLines.size(); m++) {
         LineRef cand = allLines.get(m);
         if(cand.line instanceof AsmComment || cand.line instanceof AsmConstant || cand.line instanceof AsmLabelDecl) continue;
         if(cand.line instanceof AsmLabel) continue;
         if(cand.line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) cand.line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) return false;
            String mnem = op.getMnemonic();
            CpuAddressingMode mode = op.getAddressingMode();

            if("A".equals(reg)) {
               if(mnem.equals("lda") || mnem.equals("pla") || mnem.equals("txa") || mnem.equals("tya") || mnem.equals("lax")) {
                  return true;
               }
               if(mnem.equals("sta") || mnem.equals("tax") || mnem.equals("tay") || mnem.equals("pha") ||
                  mnem.equals("cmp") || mnem.equals("adc") || mnem.equals("sbc") || mnem.equals("and") ||
                  mnem.equals("ora") || mnem.equals("eor") || mnem.equals("bit") || mnem.equals("dcp") || mnem.equals("sax") ||
                  mnem.equals("alr") || mnem.equals("anc") || mnem.equals("arr") || mnem.equals("axs") ||
                  mnem.equals("isc") || mnem.equals("rla") || mnem.equals("rra") ||
                  mnem.equals("slo") || mnem.equals("sre") ||
                  ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) && CpuAddressingMode.NON.equals(mode))) {
                  return false;
               }
            } else if("X".equals(reg)) {
               if(mnem.equals("ldx") || mnem.equals("tax") || mnem.equals("tsx") || mnem.equals("lax")) {
                  return true;
               }
               if(mnem.equals("stx") || mnem.equals("cpx") || mnem.equals("inx") || mnem.equals("dex") ||
                  mnem.equals("txa") || mnem.equals("txs") || mnem.equals("axs") || mnem.equals("sax") ||
                  mnem.equals("phx") ||
                  mode.equals(CpuAddressingMode.ZPX) || mode.equals(CpuAddressingMode.ABX) || mode.equals(CpuAddressingMode.IZX)) {
                  return false;
               }
            } else if("Y".equals(reg)) {
               if(mnem.equals("ldy") || mnem.equals("tay") || mnem.equals("ply")) {
                  return true;
               }
               if(mnem.equals("sty") || mnem.equals("cpy") || mnem.equals("iny") || mnem.equals("dey") ||
                  mnem.equals("tya") || mnem.equals("phy") ||
                  mode.equals(CpuAddressingMode.ZPY) || mode.equals(CpuAddressingMode.ABY) || mode.equals(CpuAddressingMode.IZY)) {
                  return false;
               }
            } else {
               return false;
            }

            if(mnem.equals("jsr") || mnem.equals("rti")) {
               // Conservative: subroutines may read A/X/Y parameters; rti returns to interrupted context
               return false;
            }
            if(mnem.equals("rts")) {
               if(currentScope != null && program != null) {
                  Symbol sym = program.getScope().findSymbol(currentScope);
                  if(sym instanceof Procedure proc) {
                     if(SymbolType.VOID.equals(proc.getReturnType()) || proc.isCarryReturn()) {
                        return true;
                     }
                     if(proc.isAReturn()) {
                        return !"A".equals(reg);
                     }
                     if(proc.isAxConvention()) {
                        return !"A".equals(reg) && !"X".equals(reg);
                     }
                     var retVar = proc.getLocalVar("return");
                     if(retVar != null && retVar.getAllocation() != null) {
                        var rType = retVar.getAllocation().getType();
                        if(dk.camelot64.kickc.model.Registers.RegisterType.REG_A.equals(rType)) return !"A".equals(reg);
                        if(dk.camelot64.kickc.model.Registers.RegisterType.REG_X.equals(rType)) return !"X".equals(reg);
                        if(dk.camelot64.kickc.model.Registers.RegisterType.REG_Y.equals(rType)) return !"Y".equals(reg);
                        return true;
                     }
                  }
               }
               return false;
            }
            if(mnem.equals("jmp")) {
               String target = inst.getOperand1();
               if(target != null && visitedLabels.add(target.trim())) {
                  int targetIdx = findLabelIndex(allLines, target.trim(), m);
                  if(targetIdx != -1) {
                     return isRegDeadAlongPath(reg, allLines, targetIdx + 1, visitedLabels, currentScope, program);
                  }
               }
               return false;
            }
            if(mnem.startsWith("b")) {
               return false;
            }
            continue;
         }
         break;
      }
      return false;
   }

   // =========================================================================
   // Bit-Accurate {N, Z, C, V} Flag Liveness (FL-01)
   // =========================================================================

   /**
    * Checks whether the procedure named currentScope returns a boolean in the Carry flag.
    */
   public static boolean isProcedureCarryReturn(String currentScope, Program program) {
      if(currentScope == null || currentScope.isEmpty() || program == null || program.getScope() == null) {
         return false;
      }
      Symbol sym = program.getScope().getLocalSymbol(currentScope);
      if(sym instanceof Procedure) {
         return ((Procedure) sym).isCarryReturn();
      }
      return false;
   }

   /**
    * Returns the bitmask of status flags {N, Z, C, V} READ (gen) by the given instruction.
    */
   public static int getFlagsRead(AsmInstruction inst, boolean procIsCarryReturn) {
      if(inst == null || inst.getCpuOpcode() == null) return FLAG_ALL;
      String mnem = inst.getCpuOpcode().getMnemonic();
      switch(mnem) {
         case "beq":
         case "bne":
            return FLAG_Z;
         case "bmi":
         case "bpl":
            return FLAG_N;
         case "bcc":
         case "bcs":
            return FLAG_C;
         case "bvc":
         case "bvs":
            return FLAG_V;
         case "adc":
         case "sbc":
         case "rol":
         case "ror":
         case "arr":
         case "rla":
         case "rra":
         case "isc":
            return FLAG_C;
         case "php":
            return FLAG_ALL;
         case "rts":
            return procIsCarryReturn ? FLAG_C : FLAG_NONE;
         case "rti":
            return FLAG_NONE;
         default:
            return FLAG_NONE;
      }
   }

   /**
    * Returns the bitmask of status flags {N, Z, C, V} MODIFIED/OVERWRITTEN (kill) by the given instruction.
    */
   public static int getFlagsModified(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return FLAG_NONE;
      String mnem = inst.getCpuOpcode().getMnemonic();
      switch(mnem) {
         case "lda":
         case "ldx":
         case "ldy":
         case "ldz":
         case "lax":
         case "pla":
         case "plx":
         case "ply":
         case "plz":
         case "tax":
         case "txa":
         case "tay":
         case "tya":
         case "taz":
         case "tza":
         case "tsx":
         case "inc":
         case "dec":
         case "inx":
         case "dex":
         case "iny":
         case "dey":
         case "inz":
         case "dez":
         case "and":
         case "ora":
         case "eor":
            return FLAG_NZ;
         case "cmp":
         case "cpx":
         case "cpy":
         case "cpz":
         case "asl":
         case "lsr":
         case "rol":
         case "ror":
         case "anc":
         case "alr":
         case "axs":
         case "dcp":
         case "slo":
         case "sre":
         case "rla":
            return FLAG_NZC;
         case "adc":
         case "sbc":
         case "arr":
         case "rra":
         case "isc":
            return FLAG_ALL;
         case "bit":
            return FLAG_NZV;
         case "clc":
         case "sec":
            return FLAG_C;
         case "clv":
            return FLAG_V;
         case "plp":
         case "jsr":
            return FLAG_ALL;
         default:
            return FLAG_NONE;
      }
   }

   /**
    * Computes the bitmask of status flags {N, Z, C, V} that may be read on any reachable path
    * starting immediately AFTER allLines.get(currentIdx) before being overwritten.
    * Follows unconditional jumps and conditional branches up to depth <= 2 (uniting fallthrough and target).
    */
   public static int getLiveFlagsAfter(List<LineRef> allLines, int currentIdx, String currentScope, Program program) {
      boolean procIsCarryReturn = isProcedureCarryReturn(currentScope, program);
      return getLiveFlagsAt(allLines, currentIdx + 1, procIsCarryReturn, 0, new HashSet<>());
   }

   private static int getLiveFlagsAt(List<LineRef> allLines, int startIdx, boolean procIsCarryReturn, int branchDepth, Set<Integer> visited) {
      int needed = FLAG_NONE;
      int killed = FLAG_NONE;

      for(int k = startIdx; k < allLines.size(); k++) {
         if(!visited.add(k)) {
            // Reached a cycle along this path; conservatively assume remaining unkilled flags may be needed
            return needed | (FLAG_ALL & ~killed);
         }
         AsmLine line = allLines.get(k).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) {
            continue;
         }
         if(line instanceof AsmLabel) {
            // Labels are join points for other incoming edges; forward flow falls straight through
            continue;
         }
         if(line instanceof AsmScopeBegin || line instanceof AsmScopeEnd || line instanceof AsmInlineKickAsm) {
            return needed | (FLAG_ALL & ~killed);
         }
         if(line instanceof AsmInstruction) {
            AsmInstruction inst = (AsmInstruction) line;
            CpuOpcode op = inst.getCpuOpcode();
            if(op == null) {
               return needed | (FLAG_ALL & ~killed);
            }
            String mnem = op.getMnemonic();

            if(mnem.equals("rts")) {
               int rtsRead = procIsCarryReturn ? FLAG_C : FLAG_NONE;
               return needed | (rtsRead & ~killed);
            }
            if(mnem.equals("rti")) {
               return needed;
            }
            if(mnem.equals("jmp")) {
               if(op.getAddressingMode().equals(CpuAddressingMode.IND) || branchDepth >= 2) {
                  return needed | (FLAG_ALL & ~killed);
               }
               int targetIdx = findLabelIndex(allLines, inst.getOperandJumpTarget(), k);
               if(targetIdx == -1) {
                  return needed | (FLAG_ALL & ~killed);
               }
               int afterJmp = getLiveFlagsAt(allLines, targetIdx + 1, procIsCarryReturn, branchDepth + 1, new HashSet<>(visited));
               return needed | (afterJmp & ~killed);
            }
            if(mnem.equals("beq") || mnem.equals("bne") || mnem.equals("bmi") || mnem.equals("bpl") ||
               mnem.equals("bcc") || mnem.equals("bcs") || mnem.equals("bvc") || mnem.equals("bvs")) {
               int branchRead = getFlagsRead(inst, procIsCarryReturn);
               needed |= (branchRead & ~killed);
               if(branchDepth >= 2) {
                  return needed | (FLAG_ALL & ~killed);
               }
               int fallthroughLive = getLiveFlagsAt(allLines, k + 1, procIsCarryReturn, branchDepth + 1, new HashSet<>(visited));
               int targetIdx = findLabelIndex(allLines, inst.getOperandJumpTarget(), k);
               int targetLive = (targetIdx != -1)
                  ? getLiveFlagsAt(allLines, targetIdx + 1, procIsCarryReturn, branchDepth + 1, new HashSet<>(visited))
                  : FLAG_ALL;
               return needed | ((fallthroughLive | targetLive) & ~killed);
            }

            int read = getFlagsRead(inst, procIsCarryReturn);
            int kill = getFlagsModified(inst);
            needed |= (read & ~killed);
            killed |= kill;
            if(killed == FLAG_ALL) {
               return needed;
            }
            continue;
         }
         // Data or unknown directives break control flow conservatively
         return needed | (FLAG_ALL & ~killed);
      }
      return needed;
   }

   /**
    * Returns true if any flag modified by the instruction at currentIdx is live after it.
    */
   public static boolean areModifiedFlagsLiveAfter(List<LineRef> allLines, int currentIdx, String currentScope, Program program) {
      AsmLine line = allLines.get(currentIdx).line;
      if(!(line instanceof AsmInstruction)) return true;
      int mod = getFlagsModified((AsmInstruction) line);
      if(mod == FLAG_NONE) return false;
      int live = getLiveFlagsAfter(allLines, currentIdx, currentScope, program);
      return (mod & live) != 0;
   }

   /**
    * Returns true if the Carry flag (C) is live after allLines.get(currentIdx).
    */
   public static boolean isFlagCLiveAfter(List<LineRef> allLines, int currentIdx, String currentScope, Program program) {
      return (getLiveFlagsAfter(allLines, currentIdx, currentScope, program) & FLAG_C) != 0;
   }

   /**
    * Returns true if either N or Z flag is live after allLines.get(currentIdx).
    */
   public static boolean areNZFlagsLiveAfter(List<LineRef> allLines, int currentIdx, String currentScope, Program program) {
      return (getLiveFlagsAfter(allLines, currentIdx, currentScope, program) & FLAG_NZ) != 0;
   }

   /**
    * Checks if an instruction preserves both Z (Zero) and N (Negative) flags on MOS 6502/6510.
    * Stores, stack pointer store, no-ops and carry/decimal/interrupt/overflow flag modifiers
    * do NOT alter the Z and N flags.
    */
   public static boolean isInstructionPreservingZAndN(AsmInstruction inst) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      // Stores do not modify flags on 6502/6510
      if(mnem.equals("sta") || mnem.equals("stx") || mnem.equals("sty") || mnem.equals("sax")) {
         return true;
      }
      // Flag instructions that modify C, D, I, or V, but leave Z and N completely untouched
      if(mnem.equals("clc") || mnem.equals("sec") || mnem.equals("cld") || mnem.equals("sed") ||
         mnem.equals("cli") || mnem.equals("sei") || mnem.equals("clv")) {
         return true;
      }
      // Stack pointer register store and no-op
      if(mnem.equals("txs") || mnem.equals("nop")) {
         return true;
      }
      return false;
   }

   /**
    * Checks if an instruction establishes the Z and N flags for the given register.
    */
   public static boolean doesInstructionSetZAndN(AsmInstruction inst, String reg) {
      if(inst == null || inst.getCpuOpcode() == null) return false;
      String mnem = inst.getCpuOpcode().getMnemonic();
      CpuAddressingMode mode = inst.getCpuOpcode().getAddressingMode();
      if("A".equals(reg)) {
         return mnem.equals("lda") || mnem.equals("pla") ||
                mnem.equals("tax") || mnem.equals("txa") ||
                mnem.equals("tay") || mnem.equals("tya") ||
                mnem.equals("and") || mnem.equals("ora") || mnem.equals("eor") ||
                mnem.equals("adc") || mnem.equals("sbc") ||
                mnem.equals("lax") || mnem.equals("alr") || mnem.equals("anc") || mnem.equals("arr") ||
                mnem.equals("isc") || mnem.equals("rla") || mnem.equals("rra") ||
                mnem.equals("slo") || mnem.equals("sre") ||
                ((mnem.equals("asl") || mnem.equals("lsr") || mnem.equals("rol") || mnem.equals("ror")) && mode.equals(CpuAddressingMode.NON));
      } else if("X".equals(reg)) {
         return mnem.equals("ldx") || mnem.equals("inx") || mnem.equals("dex") ||
                mnem.equals("tax") || mnem.equals("tsx") || mnem.equals("lax") || mnem.equals("axs");
      } else if("Y".equals(reg)) {
         return mnem.equals("ldy") || mnem.equals("iny") || mnem.equals("dey") || mnem.equals("tay");
      }
      return false;
   }

   /**
    * Scans backwards from inst2Idx to determine if the Z and N flags are guaranteed to match the given register.
    * Skips non-flag-modifying instructions (stores, CLC, SEC, etc.) and analyzes incoming predecessors if a label is hit.
    */
   public static boolean areFlagsEstablishedForReg(List<LineRef> allLines, int inst2Idx, String reg, String currentScope) {
      // 1. Scan backwards within the same basic block, skipping non-flag-modifying stores/instructions
      AsmLabel labelHit = null;
      int labelIdx = -1;
      for(int p = inst2Idx - 1; p >= 0; p--) {
         AsmLine line = allLines.get(p).line;
         if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
         if(line instanceof AsmLabel) {
            labelHit = (AsmLabel) line;
            labelIdx = p;
            break;
         }
         if(line instanceof AsmScopeBegin || line instanceof AsmScopeEnd || line instanceof AsmInlineKickAsm) {
            return false;
         }
         if(line instanceof AsmInstruction) {
            AsmInstruction prev = (AsmInstruction) line;
            if(prev.getCpuOpcode() == null) return false;
            if(isInstructionPreservingZAndN(prev)) {
               continue; // Stores and status flags (CLC, SEC, etc.) do not modify Z/N on 6502
            }
            return doesInstructionSetZAndN(prev, reg);
         }
      }

      // 2. If we hit a label at the block entry, check all incoming predecessors to this label
      if(labelHit != null && currentScope != null && !currentScope.isEmpty()) {
         Set<String> targetLabels = new HashSet<>();
         targetLabels.add(labelHit.getLabel());

         // Collect all contiguous labels immediately before or after labelHit
         int firstLabelIdx = labelIdx;
         for(int p = labelIdx - 1; p >= 0; p--) {
            AsmLine line = allLines.get(p).line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
            if(line instanceof AsmLabel) {
               targetLabels.add(((AsmLabel) line).getLabel());
               firstLabelIdx = p;
               continue;
            }
            break;
         }
         for(int p = labelIdx + 1; p < allLines.size(); p++) {
            AsmLine line = allLines.get(p).line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
            if(line instanceof AsmLabel) {
               targetLabels.add(((AsmLabel) line).getLabel());
               continue;
            }
            break;
         }

         List<Integer> predIndices = new ArrayList<>();

         // Check fallthrough immediately before firstLabelIdx
         for(int p = firstLabelIdx - 1; p >= 0; p--) {
            AsmLine line = allLines.get(p).line;
            if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
            if(line instanceof AsmInstruction) {
               AsmInstruction prevInst = (AsmInstruction) line;
               if(prevInst.getCpuOpcode() != null) {
                  String pm = prevInst.getCpuOpcode().getMnemonic();
                  if(!pm.equals("jmp") && !pm.equals("rts") && !pm.equals("rti")) {
                     predIndices.add(p);
                  }
               }
            }
            break;
         }

         // Check jumps/branches to targetLabels strictly within currentScope.
         // Fast path: locate enclosing [scopeStart..scopeEnd] around firstLabelIdx to avoid scanning the entire program.
         int scopeStart = -1;
         for(int p = firstLabelIdx; p >= 0; p--) {
            LineRef ref = allLines.get(p);
            if(ref.line instanceof AsmScopeBegin) {
               if(currentScope.equals(((AsmScopeBegin) ref.line).getLabel())) {
                  scopeStart = p;
               }
               break;
            }
            if(!currentScope.equals(ref.scope)) {
               break;
            }
         }
         int scopeEnd = -1;
         if(scopeStart != -1) {
            for(int p = firstLabelIdx; p < allLines.size(); p++) {
               LineRef ref = allLines.get(p);
               if(ref.line instanceof AsmScopeEnd || !currentScope.equals(ref.scope)) {
                  scopeEnd = p;
                  break;
               }
            }
            if(scopeEnd == -1) scopeEnd = allLines.size();
         }

         if(scopeStart != -1) {
            for(int k = scopeStart + 1; k < scopeEnd; k++) {
               LineRef ref = allLines.get(k);
               if(ref.line instanceof AsmInstruction) {
                  AsmInstruction inst = (AsmInstruction) ref.line;
                  if(inst.getCpuOpcode() != null && inst.getCpuOpcode().isJump()) {
                     String dest = inst.getOperandJumpTarget();
                     if(dest != null) {
                        for(String tLab : targetLabels) {
                           if(dest.equals(tLab) || dest.equals("!" + tLab + "+") || dest.equals(tLab + "+")) {
                              predIndices.add(k);
                              break;
                           }
                        }
                     }
                  }
               }
            }
         } else {
            // Fallback when no enclosing AsmScopeBegin was found
            String scanScope = null;
            for(int k = 0; k < allLines.size(); k++) {
               LineRef ref = allLines.get(k);
               if(ref.line instanceof AsmScopeBegin) {
                  scanScope = ((AsmScopeBegin) ref.line).getLabel();
                  continue;
               } else if(ref.line instanceof AsmScopeEnd) {
                  scanScope = null;
                  continue;
               }
               if(scanScope == null || !scanScope.equals(currentScope)) {
                  continue;
               }
               if(ref.line instanceof AsmInstruction) {
                  AsmInstruction inst = (AsmInstruction) ref.line;
                  if(inst.getCpuOpcode() != null && inst.getCpuOpcode().isJump()) {
                     String dest = inst.getOperandJumpTarget();
                     if(dest != null) {
                        for(String tLab : targetLabels) {
                           if(dest.equals(tLab) || dest.equals("!" + tLab + "+") || dest.equals(tLab + "+")) {
                              predIndices.add(k);
                              break;
                           }
                        }
                     }
                  }
               }
            }
         }

         if(predIndices.isEmpty()) return false;

         for(int predIdx : predIndices) {
            boolean predOk = false;
            for(int p = predIdx; p >= 0; p--) {
               AsmLine line = allLines.get(p).line;
               if(line instanceof AsmComment || line instanceof AsmConstant || line instanceof AsmLabelDecl) continue;
               if(line instanceof AsmInstruction) {
                  AsmInstruction pi = (AsmInstruction) line;
                  if(pi.getCpuOpcode() == null) break;
                  String pm = pi.getCpuOpcode().getMnemonic();
                  if(pm.equals("jmp")) {
                     continue; // Look before unconditional jump
                  }
                  if(pm.startsWith("b")) {
                     break; // Branches test different conditions; flags are NOT established!
                  }
                  if(isInstructionPreservingZAndN(pi)) {
                     continue; // Stores do not modify flags
                  }
                  if(doesInstructionSetZAndN(pi, reg)) {
                     predOk = true;
                  }
                  break;
               }
               break;
            }
            if(!predOk) return false;
         }
         return true;
      }

      return false;
   }

   /**
    * Checks if flags modified by the instruction at currentIdx are needed by subsequent instructions
    * before being overwritten (delegates to bit-accurate {N, Z, C, V} liveness).
    */
   public static boolean areFlagsNeededAfter(List<LineRef> allLines, int currentIdx, String currentScope, Program program) {
      AsmLine line = allLines.get(currentIdx).line;
      if(line instanceof AsmInstruction) {
         return areModifiedFlagsLiveAfter(allLines, currentIdx, currentScope, program);
      }
      return getLiveFlagsAfter(allLines, currentIdx, currentScope, program) != FLAG_NONE;
   }
}
