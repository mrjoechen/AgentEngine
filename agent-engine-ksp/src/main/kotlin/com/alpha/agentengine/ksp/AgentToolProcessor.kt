package com.alpha.agentengine.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import java.io.OutputStream

/**
 * KSP 处理器：扫描 @AgentTool 注解的函数，生成 ToolProvider 实现。
 *
 * 对于每个包含 @AgentTool 方法的类，生成一个 {ClassName}ToolProvider。
 * 对于顶层函数，按文件分组生成 {FileName}ToolProvider。
 */
class AgentToolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(AGENT_TOOL_ANNOTATION)
            .filterIsInstance<KSFunctionDeclaration>()
            .toList()

        if (symbols.isEmpty()) return emptyList()

        val deferred = symbols.filter { !it.validate() }

        // 按所属类分组（null key = 顶层函数）
        val grouped = symbols.filter { it.validate() }
            .groupBy { it.parentDeclaration as? KSClassDeclaration }

        for ((classDecl, functions) in grouped) {
            if (classDecl != null) {
                generateClassToolProvider(classDecl, functions)
            } else {
                // 顶层函数按文件分组
                val byFile = functions.groupBy { it.containingFile!! }
                for ((file, fileFunctions) in byFile) {
                    generateTopLevelToolProvider(file, fileFunctions)
                }
            }
        }

        return deferred
    }

    private fun generateClassToolProvider(
        classDecl: KSClassDeclaration,
        functions: List<KSFunctionDeclaration>
    ) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val providerName = "${className}ToolProvider"

        val file = codeGenerator.createNewFile(
            Dependencies(true, *functions.mapNotNull { it.containingFile }.toTypedArray()),
            packageName,
            providerName
        )

        file.write(buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.alpha.agentengine.core.tool.*")
            appendLine("import com.alpha.agentengine.core.tool.ParamType.*")
            appendLine()
            appendLine("/**")
            appendLine(" * 自动生成的 ToolProvider，来自 $className 中的 @AgentTool 方法。")
            appendLine(" */")
            appendLine("class $providerName(private val instance: $className) : ToolProvider {")
            appendLine("    override fun provideTools(): List<ToolDefinition> = listOf(")

            functions.forEachIndexed { index, func ->
                appendToolDefinition(func, "instance.${func.simpleName.asString()}")
                if (index < functions.size - 1) appendLine(",")
            }

            appendLine()
            appendLine("    )")
            appendLine("}")
        })

        file.close()
        logger.info("Generated $providerName for $className")
    }

    private fun generateTopLevelToolProvider(
        file: KSFile,
        functions: List<KSFunctionDeclaration>
    ) {
        val packageName = functions.first().packageName.asString()
        val fileName = file.fileName.removeSuffix(".kt")
        val providerName = "${fileName}ToolProvider"

        val outFile = codeGenerator.createNewFile(
            Dependencies(true, file),
            packageName,
            providerName
        )

        outFile.write(buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.alpha.agentengine.core.tool.*")
            appendLine("import com.alpha.agentengine.core.tool.ParamType.*")
            appendLine()
            appendLine("/**")
            appendLine(" * 自动生成的 ToolProvider，来自 ${file.fileName} 中的 @AgentTool 顶层函数。")
            appendLine(" */")
            appendLine("class $providerName : ToolProvider {")
            appendLine("    override fun provideTools(): List<ToolDefinition> = listOf(")

            functions.forEachIndexed { index, func ->
                val qualifiedCall = "${packageName}.${func.simpleName.asString()}"
                appendToolDefinition(func, qualifiedCall)
                if (index < functions.size - 1) appendLine(",")
            }

            appendLine()
            appendLine("    )")
            appendLine("}")
        })

        outFile.close()
        logger.info("Generated $providerName for ${file.fileName}")
    }

    private fun StringBuilder.appendToolDefinition(
        func: KSFunctionDeclaration,
        callExpression: String
    ) {
        val annotation = func.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AGENT_TOOL_ANNOTATION
        }

        val toolName = annotation.arguments.first { it.name?.asString() == "name" }.value as String
        val description = annotation.arguments.first { it.name?.asString() == "description" }.value as String
        val permission = annotation.arguments.firstOrNull { it.name?.asString() == "permission" }?.value as? String ?: "ALLOWED"

        val params = func.parameters.map { param ->
            val paramAnnotation = param.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PARAM_ANNOTATION
            }
            val paramDesc = (paramAnnotation?.arguments?.firstOrNull { it.name?.asString() == "description" }?.value as? String) ?: param.name?.asString() ?: ""
            val paramRequired = (paramAnnotation?.arguments?.firstOrNull { it.name?.asString() == "required" }?.value as? Boolean) ?: !param.type.resolve().isMarkedNullable
            val paramType = mapKotlinTypeToParamType(param.type.resolve())
            val paramName = param.name?.asString() ?: "arg"

            ParamInfo(paramName, paramType, paramDesc, paramRequired)
        }

        val isSuspend = func.modifiers.contains(Modifier.SUSPEND)

        appendLine("        ToolDefinition(")
        appendLine("            name = \"$toolName\",")
        appendLine("            description = \"${description.replace("\"", "\\\"")}\",")

        if (params.isNotEmpty()) {
            appendLine("            parameters = listOf(")
            params.forEachIndexed { i, p ->
                val comma = if (i < params.size - 1) "," else ""
                appendLine("                ParamDef(\"${p.name}\", ${p.type}, \"${p.description.replace("\"", "\\\"")}\", required = ${p.required})$comma")
            }
            appendLine("            ),")
        }

        appendLine("            permission = Permission.${permission},")
        appendLine("            handler = ToolHandler { params ->")

        // 构建函数调用参数
        val callArgs = params.joinToString(", ") { p ->
            val getter = if (p.required) {
                when (p.type) {
                    "StringType" -> "params.getString(\"${p.name}\")"
                    "IntegerType" -> "params.getInt(\"${p.name}\")"
                    "NumberType" -> "params.getDouble(\"${p.name}\")"
                    "BooleanType" -> "params.getBoolean(\"${p.name}\")"
                    else -> "params.getString(\"${p.name}\")"
                }
            } else {
                when (p.type) {
                    "StringType" -> "params.getStringOrNull(\"${p.name}\")"
                    "IntegerType" -> "params.getIntOrNull(\"${p.name}\")"
                    "BooleanType" -> "params.getBooleanOrNull(\"${p.name}\")"
                    else -> "params.getStringOrNull(\"${p.name}\")"
                }
            }
            "${p.name} = $getter"
        }

        // 判断返回类型
        val returnType = func.returnType?.resolve()
        val isToolResult = returnType?.declaration?.qualifiedName?.asString() == "com.alpha.agentengine.core.tool.ToolResult"

        if (isToolResult) {
            appendLine("                $callExpression($callArgs)")
        } else {
            appendLine("                val result = $callExpression($callArgs)")
            appendLine("                ToolResult.text(result.toString())")
        }

        appendLine("            }")
        append("        )")
    }

    private fun mapKotlinTypeToParamType(type: KSType): String {
        val qualifiedName = type.declaration.qualifiedName?.asString() ?: return "StringType"
        return when (qualifiedName) {
            "kotlin.String" -> "StringType"
            "kotlin.Int", "kotlin.Long" -> "IntegerType"
            "kotlin.Double", "kotlin.Float" -> "NumberType"
            "kotlin.Boolean" -> "BooleanType"
            "kotlin.collections.List" -> "ArrayType()"
            "kotlinx.serialization.json.JsonObject" -> "ObjectType()"
            else -> "StringType"
        }
    }

    private data class ParamInfo(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean
    )

    companion object {
        const val AGENT_TOOL_ANNOTATION = "com.alpha.agentengine.core.tool.annotation.AgentTool"
        const val PARAM_ANNOTATION = "com.alpha.agentengine.core.tool.annotation.Param"
    }
}

private fun OutputStream.write(text: String) {
    write(text.toByteArray())
}
