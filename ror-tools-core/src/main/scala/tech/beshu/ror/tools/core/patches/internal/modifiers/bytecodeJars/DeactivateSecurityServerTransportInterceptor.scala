/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.tools.core.patches.internal.modifiers.bytecodeJars

import org.objectweb.asm.*
import tech.beshu.ror.tools.core.patches.internal.modifiers.BytecodeJarModifier

import java.io.{File, InputStream}

private [patches] object DeactivateSecurityServerTransportInterceptor extends BytecodeJarModifier {

  override def apply(jar: File): Unit = {
    modifyFileInJar(
      jar = jar,
      filePathString = "org/elasticsearch/xpack/security/transport/SecurityServerTransportInterceptor.class",
      processFileContent = doDeactivateSecurityServerTransportInterceptor
    )
  }

  private def doDeactivateSecurityServerTransportInterceptor(moduleInputStream: InputStream) = {
    val reader = new ClassReader(moduleInputStream)
    val writer = new ClassWriter(reader, 0)
    reader.accept(new EsClassVisitor(writer), 0)
    writer.toByteArray
  }

  private class EsClassVisitor(writer: ClassWriter)
    extends ClassVisitor(Opcodes.ASM9, writer) {

    override def visitMethod(access: Int,
                             name: String,
                             descriptor: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      name match {
        case "interceptSender" =>
          new InterceptSenderReturningSenderFromParam(super.visitMethod(access, name, descriptor, signature, exceptions))
        case "interceptHandler" =>
          new InterceptHandlerReturningSenderFromParam(super.visitMethod(access, name, descriptor, signature, exceptions))
        case _ =>
          super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }

  private class InterceptSenderReturningSenderFromParam(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      underlying.visitVarInsn(Opcodes.ALOAD, 1)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitMaxs(1, 2)
      underlying.visitEnd()
    }
  }

  private class InterceptHandlerReturningSenderFromParam(underlying: MethodVisitor)
    extends MethodVisitor(Opcodes.ASM9) {

    override def visitCode(): Unit = {
      underlying.visitCode()
      underlying.visitVarInsn(Opcodes.ALOAD, 4)
      underlying.visitInsn(Opcodes.ARETURN)
      underlying.visitMaxs(1, 5)
      underlying.visitEnd()
    }
  }
}
