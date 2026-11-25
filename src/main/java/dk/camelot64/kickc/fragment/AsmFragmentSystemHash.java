package dk.camelot64.kickc.fragment;

import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateSynthesisRule;
import dk.camelot64.kickc.fragment.synthesis.AsmFragmentTemplateSynthesisRuleManager;
import dk.camelot64.kickc.model.InternalError;
import dk.camelot64.kickc.model.TargetCpu;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;

/**
 * Capable of creating a hash code that describes the total state of the ASM fragment system.
 * <p>
 * This hash is used to determine whether to use or discard the cache.
 */
public class AsmFragmentSystemHash {

   /** Hash of the fragment source files on any system where a newline is LF. */
   private Long hashLF;

   /** Hash of the fragment source files on any system where a newline is CRLF. */
   private Long hashCRLF;

   /** Last modified date for the fragment source files. */
   private long lastModified;

   public AsmFragmentSystemHash(Long hashLF, Long hashCRLF, long lastModified) {
      this.hashCRLF = hashCRLF;
      this.hashLF = hashLF;
      this.lastModified = lastModified;
   }

   /**
    * Run through the fragment folder and calculate the hash/modify time
    *
    * @param baseFragmentFolder The fragment folder
    * @param allSystems Should hash code be calculated for all systems (both LF and CRLF newlines).
    * If false hash is only calculated for the current system, which is fast.
    * If true calculate for all systems, which requires reading through all files to count newlines - so it is a lot slower.
    * @return The fragment system hash
    */
   public static AsmFragmentSystemHash calculate(Path baseFragmentFolder, boolean allSystems) {
      final boolean systemLF = isSystemLF();
      final long[] acc = new long[3]; // [0]=hashLF, [1]=hashCRLF, [2]=lastModified
      final TargetCpu.Feature[] cpuFeatures = TargetCpu.Feature.values();
      for(TargetCpu.Feature cpuFeature : cpuFeatures) {
         acc[0] += cpuFeature.getName().hashCode();
         acc[1] += cpuFeature.getName().hashCode();
         final Path cpuFeatureFolder = baseFragmentFolder.resolve(cpuFeature.getName());
         if(Files.isDirectory(cpuFeatureFolder)) {
            try {
               Files.walkFileTree(cpuFeatureFolder, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
                  @Override
                  public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                     String fileName = file.getFileName().toString();
                     if(fileName.endsWith(".asm")) {
                        long size = attrs.size();
                        if(systemLF) {
                           acc[0] += size;
                        } else {
                           acc[1] += size;
                        }
                        int nameHash = fileName.hashCode();
                        acc[0] += nameHash;
                        acc[1] += nameHash;
                        long mtime = attrs.lastModifiedTime().toMillis();
                        if(mtime > acc[2]) {
                           acc[2] = mtime;
                        }
                     }
                     return FileVisitResult.CONTINUE;
                  }
               });
            } catch(IOException e) {
               throw new InternalError("Error scanning ASM fragment folder " + cpuFeatureFolder.toAbsolutePath(), e);
            }
         }
      }
      // Also hash in all synthesis rules for all CPU's
      for(AsmFragmentTemplateSynthesisRule synthesisRule : AsmFragmentTemplateSynthesisRuleManager.getAllSynthesisRules()) {
         int ruleHash = synthesisRule.hashCode();
         acc[0] += ruleHash;
         acc[1] += ruleHash;
      }
      Long hashLF = acc[0];
      Long hashCRLF = acc[1];
      if(!allSystems) {
         if(systemLF)
            hashCRLF = null;
         else
            hashLF = null;
      } else {
         if(systemLF)
            hashCRLF = 0L;
         else
            hashLF = 0L;
      }
      return new AsmFragmentSystemHash(hashLF, hashCRLF, acc[2]);
   }

   /**
    * Does the current system use LF as newline
    *
    * @return true if the line separator is LF
    */
   static boolean isSystemLF() {
      return System.lineSeparator().length() == 1;
   }

   public String getHashStringLF() {
      return hashLF==null?null:Long.toHexString(hashLF);
   }

   public String getHashStringCRLF() {
      return hashCRLF==null?null:Long.toHexString(hashCRLF);
   }

   /** Determines if this hash mathes the passed hash values.
    * Only examines the value relevant for the current system
    * @param hashLF Hash of the fragment source files on any system where a newline is LF
    * @param hashCRLF Hash of the fragment source files on any system where a newline is CRLF
    * @return true if the hash matches
    */
   public boolean matches(String hashLF, String hashCRLF) {
      if(isSystemLF()) {
         return getHashStringLF().equals(hashLF);
      } else {
         return getHashStringCRLF().equals(hashCRLF);
      }
   }

   public long getLastModified() {
      return lastModified;
   }


}
