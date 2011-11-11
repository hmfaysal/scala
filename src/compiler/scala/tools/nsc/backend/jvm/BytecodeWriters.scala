/* NSC -- new Scala compiler
 * Copyright 2005-2011 LAMP/EPFL
 * @author  Paul Phillips
 */

package scala.tools.nsc
package backend.jvm

import ch.epfl.lamp.fjbg._
import java.io.{ DataOutputStream, OutputStream, File => JFile }
import scala.tools.nsc.io._
import scala.tools.nsc.util.ScalaClassLoader
import scala.tools.util.JavapClass
import java.util.jar.{ JarEntry, JarOutputStream, Attributes }
import Attributes.Name

/** For the last mile: turning generated bytecode in memory into
 *  something you can use.  Has implementations for writing to class
 *  files, jars, and disassembled/javap output.
 */
trait BytecodeWriters {
  val global: Global
  import global._

  private def outputDirectory(sym: Symbol): AbstractFile = (
    settings.outputDirs.outputDirFor {
      atPhase(currentRun.flattenPhase.prev)(sym.sourceFile)
    }
  )
  private def getFile(base: AbstractFile, cls: JClass, suffix: String): AbstractFile = {
    var dir = base
    val pathParts = cls.getName().split("[./]").toList
    for (part <- pathParts.init) {
      dir = dir.subdirectoryNamed(part)
    }
    dir.fileNamed(pathParts.last + suffix)
  }
  private def getFile(sym: Symbol, cls: JClass, suffix: String): AbstractFile =
    getFile(outputDirectory(sym), cls, suffix)

  trait BytecodeWriter {
    def writeClass(label: String, jclass: JClass, sym: Symbol): Unit
    def close(): Unit = ()
  }

  class DirectToJarfileWriter(jfile: JFile) extends BytecodeWriter {
    val jarMainAttrs = (
      if (settings.mainClass.isDefault) Nil
      else List(Name.MAIN_CLASS -> settings.mainClass.value)
    )
    val writer = new Jar(jfile).jarWriter(jarMainAttrs: _*)

    def writeClass(label: String, jclass: JClass, sym: Symbol) {
      val path = jclass.getName + ".class"
      val out  = writer.newOutputStream(path)

      try jclass writeTo out
      finally out.flush()

      informProgress("added " + label + path + " to jar")
    }
    override def close() = writer.close()
  }

  trait JavapBytecodeWriter extends BytecodeWriter {
    val baseDir = Directory(settings.Ygenjavap.value).createDirectory()

    def emitJavap(bytes: Array[Byte], javapFile: io.File) {
      val pw    = javapFile.printWriter()
      val javap = new JavapClass(ScalaClassLoader.appLoader, pw) {
        override def findBytes(path: String): Array[Byte] = bytes
      }

      try javap(Seq("-verbose", "dummy")) foreach (_.show())
      finally pw.close()
    }
    abstract override def writeClass(label: String, jclass: JClass, sym: Symbol) {
      super.writeClass(label, jclass, sym)

      val bytes     = getFile(sym, jclass, ".class").toByteArray
      val segments  = jclass.getName().split("[./]")
      val javapFile = segments.foldLeft(baseDir: Path)(_ / _) changeExtension "javap" toFile;

      javapFile.parent.createDirectory()
      emitJavap(bytes, javapFile)
    }
  }

  trait ClassBytecodeWriter extends BytecodeWriter {
    def writeClass(label: String, jclass: JClass, sym: Symbol) {
      val outfile   = getFile(sym, jclass, ".class")
      val outstream = new DataOutputStream(outfile.bufferedOutput)

      try jclass writeTo outstream
      finally outstream.close()
      informProgress("wrote '" + label + "' to " + outfile)
    }
  }
}