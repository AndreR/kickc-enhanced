package dk.camelot64.kickc.asm;

import dk.camelot64.kickc.model.Graph;
import dk.camelot64.kickc.model.Program;
import dk.camelot64.kickc.model.StatementInfos;
import dk.camelot64.kickc.model.statements.Statement;
import dk.camelot64.kickc.model.statements.StatementSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Source-level code/data size and static cycle profiler for compiled 6502 assembly programs.
 * Aggregates byte sizes, instruction counts, and static 6502 cycles by segment, procedure/scope,
 * and C source line from the final {@link AsmProgram}, and formats human-readable (.csz) and JSON reports.
 */
public class CodeSizeProfiler {

   public static final int TOP_LIMIT = 20;

   public static final class SegmentStats {
      private final String name;
      private int bytes;
      private int instructions;
      private double cycles;

      public SegmentStats(String name) {
         this.name = name;
      }

      public String getName() {
         return name;
      }

      public int getBytes() {
         return bytes;
      }

      public int getInstructions() {
         return instructions;
      }

      public double getCycles() {
         return cycles;
      }

      void add(int lineBytes, boolean isInstruction, double lineCycles) {
         this.bytes += lineBytes;
         if(isInstruction) {
            this.instructions++;
            this.cycles += lineCycles;
         }
      }
   }

   public static final class ProcedureStats {
      private final String name;
      private String segment;
      private int bytes;
      private int instructions;
      private double cycles;

      public ProcedureStats(String name, String segment) {
         this.name = name;
         this.segment = segment;
      }

      public String getName() {
         return name;
      }

      public String getSegment() {
         return segment;
      }

      public int getBytes() {
         return bytes;
      }

      public int getInstructions() {
         return instructions;
      }

      public double getCycles() {
         return cycles;
      }

      void add(String seg, int lineBytes, boolean isInstruction, double lineCycles) {
         if(this.segment == null || "Default".equals(this.segment)) {
            this.segment = seg;
         }
         this.bytes += lineBytes;
         if(isInstruction) {
            this.instructions++;
            this.cycles += lineCycles;
         }
      }
   }

   public static final class SourceLineStats {
      private final String file;
      private final int line;
      private final String code;
      private final String scope;
      private int bytes;
      private int instructions;
      private double cycles;

      public SourceLineStats(String file, int line, String code, String scope) {
         this.file = file;
         this.line = line;
         this.code = code;
         this.scope = scope;
      }

      public String getFile() {
         return file;
      }

      public int getLine() {
         return line;
      }

      public String getCode() {
         return code;
      }

      public String getScope() {
         return scope;
      }

      public int getBytes() {
         return bytes;
      }

      public int getInstructions() {
         return instructions;
      }

      public double getCycles() {
         return cycles;
      }

      void add(int lineBytes, boolean isInstruction, double lineCycles) {
         this.bytes += lineBytes;
         if(isInstruction) {
            this.instructions++;
            this.cycles += lineCycles;
         }
      }
   }

   public static final class Report {
      private final int totalBytes;
      private final int totalInstructions;
      private final double totalCycles;
      private final List<SegmentStats> segments;
      private final List<ProcedureStats> procedures;
      private final List<ProcedureStats> topProcedures;
      private final List<ProcedureStats> topProceduresByCycles;
      private final List<SourceLineStats> sourceLines;
      private final List<SourceLineStats> topSourceLines;
      private final List<SourceLineStats> topSourceLinesByCycles;

      public Report(int totalBytes,
                    int totalInstructions,
                    double totalCycles,
                    List<SegmentStats> segments,
                    List<ProcedureStats> procedures,
                    List<ProcedureStats> topProcedures,
                    List<ProcedureStats> topProceduresByCycles,
                    List<SourceLineStats> sourceLines,
                    List<SourceLineStats> topSourceLines,
                    List<SourceLineStats> topSourceLinesByCycles) {
         this.totalBytes = totalBytes;
         this.totalInstructions = totalInstructions;
         this.totalCycles = totalCycles;
         this.segments = Collections.unmodifiableList(segments);
         this.procedures = Collections.unmodifiableList(procedures);
         this.topProcedures = Collections.unmodifiableList(topProcedures);
         this.topProceduresByCycles = Collections.unmodifiableList(topProceduresByCycles);
         this.sourceLines = Collections.unmodifiableList(sourceLines);
         this.topSourceLines = Collections.unmodifiableList(topSourceLines);
         this.topSourceLinesByCycles = Collections.unmodifiableList(topSourceLinesByCycles);
      }

      public int getTotalBytes() {
         return totalBytes;
      }

      public int getTotalInstructions() {
         return totalInstructions;
      }

      public double getTotalCycles() {
         return totalCycles;
      }

      public List<SegmentStats> getSegments() {
         return segments;
      }

      public List<ProcedureStats> getProcedures() {
         return procedures;
      }

      public List<ProcedureStats> getTopProcedures() {
         return topProcedures;
      }

      public List<ProcedureStats> getTopProceduresByCycles() {
         return topProceduresByCycles;
      }

      public List<SourceLineStats> getSourceLines() {
         return sourceLines;
      }

      public List<SourceLineStats> getTopSourceLines() {
         return topSourceLines;
      }

      public List<SourceLineStats> getTopSourceLinesByCycles() {
         return topSourceLinesByCycles;
      }
   }

   private CodeSizeProfiler() {
   }

   /**
    * Analyzes the compiled assembly program and produces aggregated code/data size and static cycle statistics.
    */
   public static Report analyze(Program program) {
      if(program == null || program.getAsm() == null) {
         return new Report(0, 0, 0.0, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
               Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
      }

      Map<Integer, StatementSource> stmtSources = new HashMap<>();
      Graph graph = program.getGraph();
      if(graph != null) {
         for(Statement stmt : graph.getAllStatements()) {
            if(stmt.getIndex() != null && stmt.getSource() != null) {
               stmtSources.put(stmt.getIndex(), stmt.getSource());
            }
         }
      }
      StatementInfos stmtInfos = null;
      if(stmtSources.isEmpty() && graph != null) {
         stmtInfos = program.getStatementInfos();
      }

      Map<String, SegmentStats> segmentMap = new LinkedHashMap<>();
      Map<String, ProcedureStats> procMap = new LinkedHashMap<>();
      Map<String, SourceLineStats> sourceLineMap = new LinkedHashMap<>();

      String currentSegment = "Default";
      String activeScope = "";
      int totalBytes = 0;
      int totalInstructions = 0;
      double totalCycles = 0.0;

      for(AsmChunk chunk : program.getAsm().getChunks()) {
         StatementSource chunkSource = resolveChunkSource(chunk, stmtSources, stmtInfos);
         String chunkScope = chunk.getScopeLabel();

         for(AsmLine line : chunk.getLines()) {
            if(line instanceof AsmSegment seg) {
               currentSegment = (seg.getName() != null && !seg.getName().isEmpty()) ? seg.getName() : "Default";
               continue;
            }
            if(line instanceof AsmScopeBegin sb) {
               activeScope = sb.getLabel() != null ? sb.getLabel() : "";
               continue;
            }
            if(line instanceof AsmScopeEnd) {
               activeScope = "";
               continue;
            }

            int lineBytes = line.getLineBytes();
            boolean isInst = line instanceof AsmInstruction;
            if(lineBytes <= 0 && !isInst) {
               continue;
            }
            double lineCycles = isInst ? line.getLineCycles() : 0.0;

            String scopeName;
            if(!activeScope.isEmpty()) {
               scopeName = activeScope;
            } else if(chunkScope != null && !chunkScope.isEmpty()) {
               scopeName = chunkScope;
            } else {
               scopeName = "<global>";
            }

            totalBytes += lineBytes;
            if(isInst) {
               totalInstructions++;
               totalCycles += lineCycles;
            }

            segmentMap.computeIfAbsent(currentSegment, SegmentStats::new)
                  .add(lineBytes, isInst, lineCycles);

            final String segForLambda = currentSegment;
            procMap.computeIfAbsent(scopeName, k -> new ProcedureStats(k, segForLambda))
                  .add(currentSegment, lineBytes, isInst, lineCycles);

            if(chunkSource != null && chunkSource.getFileName() != null && chunkSource.getLineNumber() != null && lineBytes > 0) {
               String file = chunkSource.getFileName();
               int lineNum = chunkSource.getLineNumber();
               String srcKey = file + ":" + lineNum;
               String code = sanitizeSnippet(chunkSource.getCode());
               sourceLineMap.computeIfAbsent(srcKey, k -> new SourceLineStats(file, lineNum, code, scopeName))
                     .add(lineBytes, isInst, lineCycles);
            }
         }
      }

      List<SegmentStats> segments = new ArrayList<>(segmentMap.values());
      segments.sort(Comparator.comparingInt(SegmentStats::getBytes).reversed()
            .thenComparing(SegmentStats::getName));

      List<ProcedureStats> procedures = new ArrayList<>(procMap.values());
      procedures.sort(Comparator.comparingInt(ProcedureStats::getBytes).reversed()
            .thenComparing(ProcedureStats::getName));
      List<ProcedureStats> topProcedures = new ArrayList<>(procedures.subList(0, Math.min(TOP_LIMIT, procedures.size())));

      List<ProcedureStats> topProceduresByCycles = new ArrayList<>();
      for(ProcedureStats p : procMap.values()) {
         if(p.getCycles() > 0.0) {
            topProceduresByCycles.add(p);
         }
      }
      topProceduresByCycles.sort(Comparator.comparingDouble(ProcedureStats::getCycles).reversed()
            .thenComparing(Comparator.comparingInt(ProcedureStats::getBytes).reversed())
            .thenComparing(ProcedureStats::getName));
      if(topProceduresByCycles.size() > TOP_LIMIT) {
         topProceduresByCycles = new ArrayList<>(topProceduresByCycles.subList(0, TOP_LIMIT));
      }

      List<SourceLineStats> sourceLines = new ArrayList<>(sourceLineMap.values());
      sourceLines.sort(Comparator.comparing(SourceLineStats::getFile)
            .thenComparingInt(SourceLineStats::getLine));

      List<SourceLineStats> topSourceLines = new ArrayList<>(sourceLineMap.values());
      topSourceLines.sort(Comparator.comparingInt(SourceLineStats::getBytes).reversed()
            .thenComparing(SourceLineStats::getFile)
            .thenComparingInt(SourceLineStats::getLine));
      if(topSourceLines.size() > TOP_LIMIT) {
         topSourceLines = new ArrayList<>(topSourceLines.subList(0, TOP_LIMIT));
      }

      List<SourceLineStats> topSourceLinesByCycles = new ArrayList<>();
      for(SourceLineStats sl : sourceLineMap.values()) {
         if(sl.getCycles() > 0.0) {
            topSourceLinesByCycles.add(sl);
         }
      }
      topSourceLinesByCycles.sort(Comparator.comparingDouble(SourceLineStats::getCycles).reversed()
            .thenComparing(Comparator.comparingInt(SourceLineStats::getBytes).reversed())
            .thenComparing(SourceLineStats::getFile)
            .thenComparingInt(SourceLineStats::getLine));
      if(topSourceLinesByCycles.size() > TOP_LIMIT) {
         topSourceLinesByCycles = new ArrayList<>(topSourceLinesByCycles.subList(0, TOP_LIMIT));
      }

      return new Report(totalBytes, totalInstructions, totalCycles, segments, procedures, topProcedures,
            topProceduresByCycles, sourceLines, topSourceLines, topSourceLinesByCycles);
   }

   private static StatementSource resolveChunkSource(AsmChunk chunk, Map<Integer, StatementSource> stmtSources, StatementInfos stmtInfos) {
      Integer stmtIdx = chunk.getStatementIdx();
      if(stmtIdx == null) {
         return null;
      }
      StatementSource src = stmtSources.get(stmtIdx);
      if(src != null) {
         return src;
      }
      if(stmtInfos != null) {
         Statement stmt = stmtInfos.getStatement(stmtIdx);
         if(stmt != null) {
            return stmt.getSource();
         }
      }
      return null;
   }

   private static String sanitizeSnippet(String code) {
      if(code == null) return "";
      return code.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
   }

   /**
    * Generates the human-readable {@code .csz} report and/or JSON report on disk.
    */
   public static void generateReports(Program program, Path cszOutputPath, Path jsonOutputPath) throws IOException {
      Report report = analyze(program);
      if(cszOutputPath != null) {
         if(cszOutputPath.getParent() != null) {
            Files.createDirectories(cszOutputPath.getParent());
         }
         Files.writeString(cszOutputPath, formatText(report), StandardCharsets.UTF_8);
      }
      if(jsonOutputPath != null) {
         if(jsonOutputPath.getParent() != null) {
            Files.createDirectories(jsonOutputPath.getParent());
         }
         Files.writeString(jsonOutputPath, formatJson(report), StandardCharsets.UTF_8);
      }
   }

   /**
    * Formats the size and static cycle profile report in human-readable {@code .csz} text format.
    */
   public static String formatText(Report report) {
      StringBuilder sb = new StringBuilder();
      sb.append("=== Code Size Profile (.csz) ===\n");
      sb.append(String.format(Locale.ROOT, "Total Bytes: %d | Instructions: %d | Static Cycles: %s | Segments: %d | Procedures: %d | Source Lines: %d%n%n",
            report.getTotalBytes(), report.getTotalInstructions(), formatCycles(report.getTotalCycles()),
            report.getSegments().size(), report.getProcedures().size(), report.getSourceLines().size()));

      sb.append("--- Segment Summary ---\n");
      sb.append(String.format(Locale.ROOT, "%-24s %10s %8s %12s %10s%n", "Segment", "Bytes", "%Total", "Instructions", "Cycles"));
      for(SegmentStats seg : report.getSegments()) {
         double pct = report.getTotalBytes() > 0 ? (100.0 * seg.getBytes() / report.getTotalBytes()) : 0.0;
         sb.append(String.format(Locale.ROOT, "%-24s %10d %7.2f%% %12d %10s%n",
               seg.getName(), seg.getBytes(), pct, seg.getInstructions(), formatCycles(seg.getCycles())));
      }
      sb.append('\n');

      sb.append("--- Top 20 Largest Functions / Scopes ---\n");
      sb.append(String.format(Locale.ROOT, "%-4s %-32s %-18s %8s %8s %8s %10s%n", "Rank", "Procedure", "Segment", "Bytes", "%Total", "Insts", "Cycles"));
      int rank = 1;
      for(ProcedureStats proc : report.getTopProcedures()) {
         double pct = report.getTotalBytes() > 0 ? (100.0 * proc.getBytes() / report.getTotalBytes()) : 0.0;
         sb.append(String.format(Locale.ROOT, "%-4d %-32s %-18s %8d %7.2f%% %8d %10s%n",
               rank++, proc.getName(), proc.getSegment(), proc.getBytes(), pct, proc.getInstructions(), formatCycles(proc.getCycles())));
      }
      sb.append('\n');

      sb.append("--- Top 20 Costliest Functions / Scopes (Static Cycles) ---\n");
      sb.append(String.format(Locale.ROOT, "%-4s %-32s %-18s %10s %8s %8s%n", "Rank", "Procedure", "Segment", "Cycles", "Insts", "Bytes"));
      rank = 1;
      for(ProcedureStats proc : report.getTopProceduresByCycles()) {
         sb.append(String.format(Locale.ROOT, "%-4d %-32s %-18s %10s %8d %8d%n",
               rank++, proc.getName(), proc.getSegment(), formatCycles(proc.getCycles()), proc.getInstructions(), proc.getBytes()));
      }
      sb.append('\n');

      sb.append("--- Top 20 Costliest C Source Lines ---\n");
      sb.append(String.format(Locale.ROOT, "%-4s %-36s %8s %6s %8s %-20s %s%n", "Rank", "Location", "Bytes", "Insts", "Cycles", "Scope", "Code"));
      rank = 1;
      for(SourceLineStats sl : report.getTopSourceLines()) {
         String loc = sl.getFile() + ":" + sl.getLine();
         sb.append(String.format(Locale.ROOT, "%-4d %-36s %8d %6d %8s %-20s %s%n",
               rank++, loc, sl.getBytes(), sl.getInstructions(), formatCycles(sl.getCycles()), sl.getScope(), truncate(sl.getCode(), 72)));
      }
      sb.append('\n');

      sb.append("--- Top 20 Costliest C Source Lines (Static Cycles) ---\n");
      sb.append(String.format(Locale.ROOT, "%-4s %-36s %8s %6s %8s %-20s %s%n", "Rank", "Location", "Cycles", "Insts", "Bytes", "Scope", "Code"));
      rank = 1;
      for(SourceLineStats sl : report.getTopSourceLinesByCycles()) {
         String loc = sl.getFile() + ":" + sl.getLine();
         sb.append(String.format(Locale.ROOT, "%-4d %-36s %8s %6d %8d %-20s %s%n",
               rank++, loc, formatCycles(sl.getCycles()), sl.getInstructions(), sl.getBytes(), sl.getScope(), truncate(sl.getCode(), 72)));
      }
      sb.append('\n');

      sb.append("--- Source-Line Overview ---\n");
      sb.append(String.format(Locale.ROOT, "%-36s %8s %6s %8s %-20s %s%n", "Location", "Bytes", "Insts", "Cycles", "Scope", "Code"));
      for(SourceLineStats sl : report.getSourceLines()) {
         String loc = sl.getFile() + ":" + sl.getLine();
         sb.append(String.format(Locale.ROOT, "%-36s %8d %6d %8s %-20s %s%n",
               loc, sl.getBytes(), sl.getInstructions(), formatCycles(sl.getCycles()), sl.getScope(), truncate(sl.getCode(), 72)));
      }

      return sb.toString();
   }

   /**
    * Formats the size and static cycle profile report as valid structured JSON without external dependencies.
    */
   public static String formatJson(Report report) {
      StringBuilder sb = new StringBuilder();
      sb.append("{\n");
      sb.append("  \"totalBytes\": ").append(report.getTotalBytes()).append(",\n");
      sb.append("  \"totalInstructions\": ").append(report.getTotalInstructions()).append(",\n");
      sb.append("  \"totalCycles\": ").append(formatCycles(report.getTotalCycles())).append(",\n");

      sb.append("  \"segments\": [\n");
      List<SegmentStats> segs = report.getSegments();
      for(int i = 0; i < segs.size(); i++) {
         SegmentStats s = segs.get(i);
         sb.append("    {\"name\": \"").append(escapeJson(s.getName())).append("\", \"bytes\": ")
               .append(s.getBytes()).append(", \"instructions\": ").append(s.getInstructions())
               .append(", \"cycles\": ").append(formatCycles(s.getCycles())).append("}");
         sb.append(i + 1 < segs.size() ? ",\n" : "\n");
      }
      sb.append("  ],\n");

      sb.append("  \"topProcedures\": [\n");
      appendProceduresJson(sb, report.getTopProcedures());
      sb.append("  ],\n");

      sb.append("  \"topProceduresByCycles\": [\n");
      appendProceduresJson(sb, report.getTopProceduresByCycles());
      sb.append("  ],\n");

      sb.append("  \"procedures\": [\n");
      appendProceduresJson(sb, report.getProcedures());
      sb.append("  ],\n");

      sb.append("  \"topSourceLines\": [\n");
      appendSourceLinesJson(sb, report.getTopSourceLines());
      sb.append("  ],\n");

      sb.append("  \"topSourceLinesByCycles\": [\n");
      appendSourceLinesJson(sb, report.getTopSourceLinesByCycles());
      sb.append("  ],\n");

      sb.append("  \"sourceLines\": [\n");
      appendSourceLinesJson(sb, report.getSourceLines());
      sb.append("  ]\n");

      sb.append("}\n");
      return sb.toString();
   }

   private static void appendProceduresJson(StringBuilder sb, List<ProcedureStats> procs) {
      for(int i = 0; i < procs.size(); i++) {
         ProcedureStats p = procs.get(i);
         sb.append("    {\"name\": \"").append(escapeJson(p.getName()))
               .append("\", \"segment\": \"").append(escapeJson(p.getSegment()))
               .append("\", \"bytes\": ").append(p.getBytes())
               .append(", \"instructions\": ").append(p.getInstructions())
               .append(", \"cycles\": ").append(formatCycles(p.getCycles())).append("}");
         sb.append(i + 1 < procs.size() ? ",\n" : "\n");
      }
   }

   private static void appendSourceLinesJson(StringBuilder sb, List<SourceLineStats> lines) {
      for(int i = 0; i < lines.size(); i++) {
         SourceLineStats sl = lines.get(i);
         sb.append("    {\"file\": \"").append(escapeJson(sl.getFile()))
               .append("\", \"line\": ").append(sl.getLine())
               .append(", \"bytes\": ").append(sl.getBytes())
               .append(", \"instructions\": ").append(sl.getInstructions())
               .append(", \"cycles\": ").append(formatCycles(sl.getCycles()))
               .append(", \"scope\": \"").append(escapeJson(sl.getScope()))
               .append("\", \"code\": \"").append(escapeJson(sl.getCode())).append("\"}");
         sb.append(i + 1 < lines.size() ? ",\n" : "\n");
      }
   }

   static String formatCycles(double cycles) {
      if(Math.abs(cycles - Math.rint(cycles)) < 1e-6) {
         return String.format(Locale.ROOT, "%.0f", cycles);
      }
      return String.format(Locale.ROOT, "%.1f", cycles);
   }

   private static String truncate(String s, int maxLen) {
      if(s == null) return "";
      if(s.length() <= maxLen) return s;
      return s.substring(0, maxLen - 3) + "...";
   }

   static String escapeJson(String s) {
      if(s == null) return "";
      StringBuilder out = new StringBuilder(s.length());
      for(int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         switch(c) {
            case '"' -> out.append("\\\"");
            case '\\' -> out.append("\\\\");
            case '\b' -> out.append("\\b");
            case '\f' -> out.append("\\f");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            default -> {
               if(c < 0x20) {
                  out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
               } else {
                  out.append(c);
               }
            }
         }
      }
      return out.toString();
   }
}
