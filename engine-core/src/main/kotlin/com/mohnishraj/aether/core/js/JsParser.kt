package com.mohnishraj.aether.core.js

import com.mohnishraj.aether.core.js.ast.JsExpression
import com.mohnishraj.aether.core.js.ast.JsProgram
import com.mohnishraj.aether.core.js.ast.JsStatement
import com.mohnishraj.aether.core.js.ast.ObjectProperty
import com.mohnishraj.aether.core.js.ast.VariableDeclarator
import com.mohnishraj.aether.core.js.ast.VariableKind


data class JsParseResult(
    val program: JsProgram,
    val issues: List<JsIssue>,
    val tokenCount: Int,
    val astNodeCount: Int
) {
    val hasErrors: Boolean get() = issues.any { it.severity == JsIssueSeverity.ERROR }
}

class JsParser(
    private val tokens: List<JsToken>,
    lexerIssues: List<JsIssue> = emptyList(),
    private val limits: JsLimits = JsLimits()
) {
    private val issues = lexerIssues.toMutableList()
    private var current = 0
    private var nodeCount = 0
    private var statementCount = 0
    private var functionDepth = 0
    private var loopDepth = 0

    fun parse(): JsParseResult {
        val statements = mutableListOf<JsStatement>()
        val start = peek().span.start
        while (!isAtEnd() && statementCount < limits.maxStatements && nodeCount < limits.maxAstNodes) {
            val before = current
            declaration()?.let(statements::add)
            if (current == before) advance()
        }
        if (statementCount >= limits.maxStatements) issue("statement-limit", "Statement limit ${limits.maxStatements} reached", peek().span)
        if (nodeCount >= limits.maxAstNodes) issue("ast-limit", "AST node limit ${limits.maxAstNodes} reached", peek().span)
        val program = JsProgram(statements, JsSourceSpan(start, previousOrPeek().span.end)).also(::count)
        return JsParseResult(program, immutableCopy(issues), tokens.size, nodeCount)
    }

    private fun declaration(): JsStatement? = try {
        when {
            match(JsTokenType.FUNCTION) -> functionDeclaration(previous())
            match(JsTokenType.LET, JsTokenType.CONST, JsTokenType.VAR) -> variableDeclaration(previous(), consumeSemicolon = true)
            else -> statement()
        }
    } catch (failure: ParseFailure) {
        issues += failure.issue
        synchronize()
        null
    }

    private fun functionDeclaration(keyword: JsToken): JsStatement.FunctionDeclaration {
        val name = consume(JsTokenType.IDENTIFIER, "Expected function name")
        val function = parseFunctionTail(keyword.span.start)
        return JsStatement.FunctionDeclaration(name.lexeme, function.first, function.second, spanFrom(keyword, function.second.span)).also { countStatement() }
    }

    private fun parseFunctionTail(start: JsSourcePosition): Pair<List<String>, JsStatement.Block> {
        consume(JsTokenType.LEFT_PAREN, "Expected '(' after function name")
        val parameters = mutableListOf<String>()
        if (!check(JsTokenType.RIGHT_PAREN)) {
            do {
                if (parameters.size >= limits.maxCallArguments) throw error(peek(), "parameter-limit", "Too many function parameters")
                parameters += consume(JsTokenType.IDENTIFIER, "Expected parameter name").lexeme
            } while (match(JsTokenType.COMMA))
        }
        consume(JsTokenType.RIGHT_PAREN, "Expected ')' after parameters")
        consume(JsTokenType.LEFT_BRACE, "Expected '{' before function body")
        functionDepth++
        val body = try { block(previous()) } finally { functionDepth-- }
        return parameters to JsStatement.Block(body.statements, JsSourceSpan(start, body.span.end)).also(::count)
    }

    private fun variableDeclaration(keyword: JsToken, consumeSemicolon: Boolean): JsStatement.VariableDeclaration {
        val kind = when (keyword.type) {
            JsTokenType.CONST -> VariableKind.CONST
            JsTokenType.VAR -> VariableKind.VAR
            else -> VariableKind.LET
        }
        val declarations = mutableListOf<VariableDeclarator>()
        do {
            val name = consume(JsTokenType.IDENTIFIER, "Expected variable name")
            val initializer = if (match(JsTokenType.EQUAL)) expression() else null
            if (kind == VariableKind.CONST && initializer == null) throw error(name, "const-without-initializer", "Const declaration requires an initializer")
            declarations += VariableDeclarator(name.lexeme, initializer, JsSourceSpan(name.span.start, initializer?.span?.end ?: name.span.end))
        } while (match(JsTokenType.COMMA))
        if (consumeSemicolon) semicolon()
        return JsStatement.VariableDeclaration(kind, declarations, JsSourceSpan(keyword.span.start, previous().span.end)).also { countStatement() }
    }

    private fun statement(): JsStatement = when {
        match(JsTokenType.SEMICOLON) -> JsStatement.Empty(previous().span).also { countStatement() }
        match(JsTokenType.LEFT_BRACE) -> block(previous())
        match(JsTokenType.IF) -> ifStatement(previous())
        match(JsTokenType.WHILE) -> whileStatement(previous())
        match(JsTokenType.FOR) -> forStatement(previous())
        match(JsTokenType.RETURN) -> returnStatement(previous())
        match(JsTokenType.THROW) -> throwStatement(previous())
        match(JsTokenType.TRY) -> tryStatement(previous())
        match(JsTokenType.BREAK) -> breakStatement(previous())
        match(JsTokenType.CONTINUE) -> continueStatement(previous())
        else -> expressionStatement()
    }

    private fun block(open: JsToken): JsStatement.Block {
        val statements = mutableListOf<JsStatement>()
        while (!check(JsTokenType.RIGHT_BRACE) && !isAtEnd()) declaration()?.let(statements::add)
        val close = consume(JsTokenType.RIGHT_BRACE, "Expected '}' after block")
        return JsStatement.Block(statements, JsSourceSpan(open.span.start, close.span.end)).also { countStatement() }
    }

    private fun ifStatement(keyword: JsToken): JsStatement.If {
        consume(JsTokenType.LEFT_PAREN, "Expected '(' after if")
        val test = expression()
        consume(JsTokenType.RIGHT_PAREN, "Expected ')' after condition")
        val consequent = statement()
        val alternate = if (match(JsTokenType.ELSE)) statement() else null
        return JsStatement.If(test, consequent, alternate, JsSourceSpan(keyword.span.start, (alternate ?: consequent).span.end)).also { countStatement() }
    }

    private fun whileStatement(keyword: JsToken): JsStatement.While {
        consume(JsTokenType.LEFT_PAREN, "Expected '(' after while")
        val test = expression()
        consume(JsTokenType.RIGHT_PAREN, "Expected ')' after condition")
        loopDepth++
        val body = try { statement() } finally { loopDepth-- }
        return JsStatement.While(test, body, JsSourceSpan(keyword.span.start, body.span.end)).also { countStatement() }
    }

    private fun forStatement(keyword: JsToken): JsStatement {
        consume(JsTokenType.LEFT_PAREN, "Expected '(' after for")
        if (match(JsTokenType.LET, JsTokenType.CONST, JsTokenType.VAR)) {
            val declarationKeyword = previous()
            val kind = when (declarationKeyword.type) {
                JsTokenType.CONST -> VariableKind.CONST
                JsTokenType.VAR -> VariableKind.VAR
                else -> VariableKind.LET
            }
            val name = consume(JsTokenType.IDENTIFIER, "Expected variable name")
            if (match(JsTokenType.OF)) {
                val iterable = expression()
                consume(JsTokenType.RIGHT_PAREN, "Expected ')' after for-of iterable")
                loopDepth++
                val body = try { statement() } finally { loopDepth-- }
                return JsStatement.ForOf(kind, name.lexeme, iterable, body, JsSourceSpan(keyword.span.start, body.span.end)).also { countStatement() }
            }
            val declarations = mutableListOf<VariableDeclarator>()
            val initializer = if (match(JsTokenType.EQUAL)) expression() else null
            if (kind == VariableKind.CONST && initializer == null) throw error(name, "const-without-initializer", "Const declaration requires an initializer")
            declarations += VariableDeclarator(name.lexeme, initializer, JsSourceSpan(name.span.start, initializer?.span?.end ?: name.span.end))
            while (match(JsTokenType.COMMA)) {
                val nextName = consume(JsTokenType.IDENTIFIER, "Expected variable name")
                val nextInitializer = if (match(JsTokenType.EQUAL)) expression() else null
                if (kind == VariableKind.CONST && nextInitializer == null) throw error(nextName, "const-without-initializer", "Const declaration requires an initializer")
                declarations += VariableDeclarator(nextName.lexeme, nextInitializer, JsSourceSpan(nextName.span.start, nextInitializer?.span?.end ?: nextName.span.end))
            }
            consume(JsTokenType.SEMICOLON, "Expected ';' after for initializer")
            val initializerStatement = JsStatement.VariableDeclaration(
                kind,
                declarations,
                JsSourceSpan(declarationKeyword.span.start, previous().span.end)
            ).also { countStatement() }
            val test = if (!check(JsTokenType.SEMICOLON)) expression() else null
            consume(JsTokenType.SEMICOLON, "Expected ';' after for condition")
            val update = if (!check(JsTokenType.RIGHT_PAREN)) expression() else null
            consume(JsTokenType.RIGHT_PAREN, "Expected ')' after for clauses")
            loopDepth++
            val body = try { statement() } finally { loopDepth-- }
            return JsStatement.For(initializerStatement, test, update, body, JsSourceSpan(keyword.span.start, body.span.end)).also { countStatement() }
        }
        val initializer = if (match(JsTokenType.SEMICOLON)) null else expressionStatement(requireSemicolon = true)
        val test = if (!check(JsTokenType.SEMICOLON)) expression() else null
        consume(JsTokenType.SEMICOLON, "Expected ';' after for condition")
        val update = if (!check(JsTokenType.RIGHT_PAREN)) expression() else null
        consume(JsTokenType.RIGHT_PAREN, "Expected ')' after for clauses")
        loopDepth++
        val body = try { statement() } finally { loopDepth-- }
        return JsStatement.For(initializer, test, update, body, JsSourceSpan(keyword.span.start, body.span.end)).also { countStatement() }
    }

    private fun returnStatement(keyword: JsToken): JsStatement.Return {
        if (functionDepth == 0) issue("return-outside-function", "Return statement is outside a function", keyword.span)
        val argument = if (!check(JsTokenType.SEMICOLON) && !check(JsTokenType.RIGHT_BRACE) && !isAtEnd() && peek().span.start.line == keyword.span.end.line) expression() else null
        semicolon()
        return JsStatement.Return(argument, JsSourceSpan(keyword.span.start, previous().span.end)).also { countStatement() }
    }

    private fun throwStatement(keyword: JsToken): JsStatement.Throw {
        if (peek().span.start.line > keyword.span.end.line) throw error(peek(), "newline-after-throw", "A line terminator is not allowed after throw")
        val argument = expression()
        semicolon()
        return JsStatement.Throw(argument, JsSourceSpan(keyword.span.start, previous().span.end)).also { countStatement() }
    }

    private fun tryStatement(keyword: JsToken): JsStatement.Try {
        val open = consume(JsTokenType.LEFT_BRACE, "Expected '{' after try")
        val tryBlock = block(open)
        var catchParameter: String? = null
        var catchBlock: JsStatement.Block? = null
        var finalizer: JsStatement.Block? = null
        if (match(JsTokenType.CATCH)) {
            consume(JsTokenType.LEFT_PAREN, "Expected '(' after catch")
            catchParameter = consume(JsTokenType.IDENTIFIER, "Expected catch parameter").lexeme
            consume(JsTokenType.RIGHT_PAREN, "Expected ')' after catch parameter")
            catchBlock = block(consume(JsTokenType.LEFT_BRACE, "Expected '{' before catch body"))
        }
        if (match(JsTokenType.FINALLY)) {
            finalizer = block(consume(JsTokenType.LEFT_BRACE, "Expected '{' before finally body"))
        }
        if (catchBlock == null && finalizer == null) throw error(previous(), "try-without-handler", "Try statement requires catch or finally")
        val end = (finalizer ?: catchBlock ?: tryBlock).span.end
        return JsStatement.Try(tryBlock, catchParameter, catchBlock, finalizer, JsSourceSpan(keyword.span.start, end)).also { countStatement() }
    }

    private fun breakStatement(keyword: JsToken): JsStatement.Break {
        if (loopDepth == 0) issue("break-outside-loop", "Break statement is outside a loop", keyword.span)
        semicolon()
        return JsStatement.Break(JsSourceSpan(keyword.span.start, previous().span.end)).also { countStatement() }
    }

    private fun continueStatement(keyword: JsToken): JsStatement.Continue {
        if (loopDepth == 0) issue("continue-outside-loop", "Continue statement is outside a loop", keyword.span)
        semicolon()
        return JsStatement.Continue(JsSourceSpan(keyword.span.start, previous().span.end)).also { countStatement() }
    }

    private fun expressionStatement(requireSemicolon: Boolean = false): JsStatement.Expression {
        val expression = expression()
        if (requireSemicolon) consume(JsTokenType.SEMICOLON, "Expected ';' after expression") else semicolon()
        return JsStatement.Expression(expression, JsSourceSpan(expression.span.start, previous().span.end)).also { countStatement() }
    }

    private fun semicolon() {
        if (match(JsTokenType.SEMICOLON)) return
        if (check(JsTokenType.RIGHT_BRACE) || isAtEnd()) return
        if (previous().span.end.line < peek().span.start.line) return
        throw error(peek(), "missing-semicolon", "Expected ';' after statement")
    }

    private fun expression(): JsExpression = assignment()

    private fun assignment(): JsExpression {
        tryArrowFunction()?.let { return it }
        val target = conditional()
        if (match(JsTokenType.EQUAL, JsTokenType.PLUS_EQUAL, JsTokenType.MINUS_EQUAL, JsTokenType.STAR_EQUAL, JsTokenType.SLASH_EQUAL, JsTokenType.PERCENT_EQUAL)) {
            val operator = previous()
            val value = assignment()
            if (target !is JsExpression.Identifier && target !is JsExpression.Member) throw error(operator, "invalid-assignment-target", "Invalid assignment target")
            return JsExpression.Assignment(target, operator.lexeme, value, JsSourceSpan(target.span.start, value.span.end)).also(::count)
        }
        return target
    }

    private fun conditional(): JsExpression {
        var expression = logicalOr()
        if (match(JsTokenType.QUESTION)) {
            val consequent = assignment()
            consume(JsTokenType.COLON, "Expected ':' in conditional expression")
            val alternate = assignment()
            expression = JsExpression.Conditional(expression, consequent, alternate, JsSourceSpan(expression.span.start, alternate.span.end)).also(::count)
        }
        return expression
    }

    private fun logicalOr(): JsExpression {
        var expression = logicalAnd()
        while (match(JsTokenType.OR_OR)) {
            val operator = previous()
            val right = logicalAnd()
            expression = JsExpression.Logical(expression, operator.lexeme, right, JsSourceSpan(expression.span.start, right.span.end)).also(::count)
        }
        return expression
    }

    private fun logicalAnd(): JsExpression {
        var expression = equality()
        while (match(JsTokenType.AND_AND)) {
            val operator = previous()
            val right = equality()
            expression = JsExpression.Logical(expression, operator.lexeme, right, JsSourceSpan(expression.span.start, right.span.end)).also(::count)
        }
        return expression
    }

    private fun equality(): JsExpression {
        var expression = comparison()
        while (match(JsTokenType.EQUAL_EQUAL, JsTokenType.BANG_EQUAL, JsTokenType.STRICT_EQUAL, JsTokenType.STRICT_NOT_EQUAL)) {
            val operator = previous()
            val right = comparison()
            expression = JsExpression.Binary(expression, operator.lexeme, right, JsSourceSpan(expression.span.start, right.span.end)).also(::count)
        }
        return expression
    }

    private fun comparison(): JsExpression {
        var expression = term()
        while (match(JsTokenType.LESS, JsTokenType.LESS_EQUAL, JsTokenType.GREATER, JsTokenType.GREATER_EQUAL)) {
            val operator = previous()
            val right = term()
            expression = JsExpression.Binary(expression, operator.lexeme, right, JsSourceSpan(expression.span.start, right.span.end)).also(::count)
        }
        return expression
    }

    private fun term(): JsExpression {
        var expression = factor()
        while (match(JsTokenType.PLUS, JsTokenType.MINUS)) {
            val operator = previous()
            val right = factor()
            expression = JsExpression.Binary(expression, operator.lexeme, right, JsSourceSpan(expression.span.start, right.span.end)).also(::count)
        }
        return expression
    }

    private fun factor(): JsExpression {
        var expression = unary()
        while (match(JsTokenType.STAR, JsTokenType.SLASH, JsTokenType.PERCENT)) {
            val operator = previous()
            val right = unary()
            expression = JsExpression.Binary(expression, operator.lexeme, right, JsSourceSpan(expression.span.start, right.span.end)).also(::count)
        }
        return expression
    }

    private fun unary(): JsExpression {
        if (match(JsTokenType.NEW)) return newExpression(previous())
        if (match(JsTokenType.BANG, JsTokenType.MINUS, JsTokenType.PLUS, JsTokenType.TILDE, JsTokenType.TYPEOF, JsTokenType.PLUS_PLUS, JsTokenType.MINUS_MINUS)) {
            val operator = previous()
            val right = unary()
            return if (operator.type == JsTokenType.PLUS_PLUS || operator.type == JsTokenType.MINUS_MINUS) {
                validateUpdateTarget(right, operator)
                JsExpression.Update(operator.lexeme, right, prefix = true, JsSourceSpan(operator.span.start, right.span.end)).also(::count)
            } else JsExpression.Unary(operator.lexeme, right, JsSourceSpan(operator.span.start, right.span.end)).also(::count)
        }
        return postfix()
    }

    private fun postfix(): JsExpression {
        var expression = call()
        if (match(JsTokenType.PLUS_PLUS, JsTokenType.MINUS_MINUS)) {
            val operator = previous()
            validateUpdateTarget(expression, operator)
            expression = JsExpression.Update(operator.lexeme, expression, prefix = false, JsSourceSpan(expression.span.start, operator.span.end)).also(::count)
        }
        return expression
    }

    private fun tryArrowFunction(): JsExpression.FunctionExpression? {
        val checkpoint = current
        val start = peek().span.start
        val parameters = mutableListOf<String>()
        when {
            check(JsTokenType.IDENTIFIER) && checkNext(JsTokenType.ARROW) -> parameters += advance().lexeme
            check(JsTokenType.LEFT_PAREN) -> {
                advance()
                if (!check(JsTokenType.RIGHT_PAREN)) {
                    do {
                        if (!check(JsTokenType.IDENTIFIER)) { current = checkpoint; return null }
                        parameters += advance().lexeme
                    } while (match(JsTokenType.COMMA))
                }
                if (!match(JsTokenType.RIGHT_PAREN) || !check(JsTokenType.ARROW)) { current = checkpoint; return null }
            }
            else -> return null
        }
        consume(JsTokenType.ARROW, "Expected '=>' after arrow parameters")
        val body = if (match(JsTokenType.LEFT_BRACE)) {
            functionDepth++
            try { block(previous()) } finally { functionDepth-- }
        } else {
            val expression = assignment()
            val returned = JsStatement.Return(expression, expression.span).also { countStatement() }
            JsStatement.Block(listOf(returned), JsSourceSpan(start, expression.span.end)).also(::count)
        }
        return JsExpression.FunctionExpression(null, parameters, body, JsSourceSpan(start, body.span.end)).also(::count)
    }

    private fun newExpression(keyword: JsToken): JsExpression {
        var constructor = primary()
        while (true) {
            constructor = when {
                match(JsTokenType.DOT) -> {
                    val property = consumePropertyName("Expected property name after '.'")
                    val key = JsExpression.Literal(JsValue.StringValue(property.lexeme), property.span).also(::count)
                    JsExpression.Member(constructor, key, false, JsSourceSpan(constructor.span.start, property.span.end)).also(::count)
                }
                match(JsTokenType.LEFT_BRACKET) -> {
                    val property = expression()
                    val close = consume(JsTokenType.RIGHT_BRACKET, "Expected ']' after property expression")
                    JsExpression.Member(constructor, property, true, JsSourceSpan(constructor.span.start, close.span.end)).also(::count)
                }
                else -> break
            }
        }
        val arguments = mutableListOf<JsExpression>()
        var end = constructor.span.end
        if (match(JsTokenType.LEFT_PAREN)) {
            if (!check(JsTokenType.RIGHT_PAREN)) {
                do {
                    if (arguments.size >= limits.maxCallArguments) throw error(peek(), "argument-limit", "Too many constructor arguments")
                    arguments += assignment()
                } while (match(JsTokenType.COMMA))
            }
            end = consume(JsTokenType.RIGHT_PAREN, "Expected ')' after constructor arguments").span.end
        }
        var result: JsExpression = JsExpression.New(constructor, arguments, JsSourceSpan(keyword.span.start, end)).also(::count)
        while (true) {
            result = when {
                match(JsTokenType.LEFT_PAREN) -> finishCall(result)
                match(JsTokenType.DOT) -> {
                    val property = consumePropertyName("Expected property name after '.'")
                    val key = JsExpression.Literal(JsValue.StringValue(property.lexeme), property.span).also(::count)
                    JsExpression.Member(result, key, false, JsSourceSpan(result.span.start, property.span.end)).also(::count)
                }
                match(JsTokenType.LEFT_BRACKET) -> {
                    val property = expression()
                    val close = consume(JsTokenType.RIGHT_BRACKET, "Expected ']' after property expression")
                    JsExpression.Member(result, property, true, JsSourceSpan(result.span.start, close.span.end)).also(::count)
                }
                else -> return result
            }
        }
    }

    private fun call(): JsExpression {
        var expression = primary()
        while (true) {
            expression = when {
                match(JsTokenType.LEFT_PAREN) -> finishCall(expression)
                match(JsTokenType.DOT) -> {
                    val property = consumePropertyName("Expected property name after '.'")
                    val propertyExpression = JsExpression.Literal(JsValue.StringValue(property.lexeme), property.span).also(::count)
                    JsExpression.Member(expression, propertyExpression, computed = false, JsSourceSpan(expression.span.start, property.span.end)).also(::count)
                }
                match(JsTokenType.LEFT_BRACKET) -> {
                    val property = expression()
                    val close = consume(JsTokenType.RIGHT_BRACKET, "Expected ']' after property expression")
                    JsExpression.Member(expression, property, computed = true, JsSourceSpan(expression.span.start, close.span.end)).also(::count)
                }
                else -> return expression
            }
        }
    }

    private fun finishCall(callee: JsExpression): JsExpression.Call {
        val arguments = mutableListOf<JsExpression>()
        if (!check(JsTokenType.RIGHT_PAREN)) {
            do {
                if (arguments.size >= limits.maxCallArguments) throw error(peek(), "argument-limit", "Too many call arguments")
                arguments += assignment()
            } while (match(JsTokenType.COMMA))
        }
        val close = consume(JsTokenType.RIGHT_PAREN, "Expected ')' after arguments")
        return JsExpression.Call(callee, arguments, JsSourceSpan(callee.span.start, close.span.end)).also(::count)
    }

    private fun primary(): JsExpression {
        if (match(JsTokenType.FALSE, JsTokenType.TRUE, JsTokenType.NULL, JsTokenType.UNDEFINED, JsTokenType.NUMBER, JsTokenType.STRING)) {
            return JsExpression.Literal(previous().literal ?: JsValue.Undefined, previous().span).also(::count)
        }
        if (match(JsTokenType.IDENTIFIER)) return JsExpression.Identifier(previous().lexeme, previous().span).also(::count)
        if (match(JsTokenType.FUNCTION)) {
            val keyword = previous()
            val name = if (match(JsTokenType.IDENTIFIER)) previous().lexeme else null
            val function = parseFunctionTail(keyword.span.start)
            return JsExpression.FunctionExpression(name, function.first, function.second, JsSourceSpan(keyword.span.start, function.second.span.end)).also(::count)
        }
        if (match(JsTokenType.LEFT_PAREN)) {
            val expression = expression()
            consume(JsTokenType.RIGHT_PAREN, "Expected ')' after expression")
            return expression
        }
        if (match(JsTokenType.LEFT_BRACKET)) return arrayLiteral(previous())
        if (match(JsTokenType.LEFT_BRACE)) return objectLiteral(previous())
        throw error(peek(), "expected-expression", "Expected expression")
    }

    private fun arrayLiteral(open: JsToken): JsExpression.ArrayLiteral {
        val elements = mutableListOf<JsExpression?>()
        if (!check(JsTokenType.RIGHT_BRACKET)) {
            do {
                if (elements.size >= limits.maxArrayElements) throw error(peek(), "array-limit", "Array literal exceeds ${limits.maxArrayElements} elements")
                elements += if (check(JsTokenType.COMMA)) null else assignment()
            } while (match(JsTokenType.COMMA) && !check(JsTokenType.RIGHT_BRACKET))
        }
        val close = consume(JsTokenType.RIGHT_BRACKET, "Expected ']' after array literal")
        return JsExpression.ArrayLiteral(elements, JsSourceSpan(open.span.start, close.span.end)).also(::count)
    }

    private fun objectLiteral(open: JsToken): JsExpression.ObjectLiteral {
        val properties = mutableListOf<ObjectProperty>()
        if (!check(JsTokenType.RIGHT_BRACE)) {
            do {
                if (properties.size >= limits.maxObjectProperties) throw error(peek(), "object-limit", "Object literal exceeds ${limits.maxObjectProperties} properties")
                val keyToken = when {
                    match(JsTokenType.IDENTIFIER, JsTokenType.STRING, JsTokenType.NUMBER) -> previous()
                    else -> throw error(peek(), "expected-property", "Expected object property name")
                }
                val key = when (val literal = keyToken.literal) {
                    is JsValue.StringValue -> literal.value
                    is JsValue.NumberValue -> literal.displayString()
                    else -> keyToken.lexeme
                }
                val value = if (match(JsTokenType.COLON)) assignment() else {
                    if (keyToken.type != JsTokenType.IDENTIFIER) throw error(keyToken, "expected-colon", "Expected ':' after property name")
                    JsExpression.Identifier(key, keyToken.span).also(::count)
                }
                properties += ObjectProperty(key, value, JsSourceSpan(keyToken.span.start, value.span.end))
            } while (match(JsTokenType.COMMA) && !check(JsTokenType.RIGHT_BRACE))
        }
        val close = consume(JsTokenType.RIGHT_BRACE, "Expected '}' after object literal")
        return JsExpression.ObjectLiteral(properties, JsSourceSpan(open.span.start, close.span.end)).also(::count)
    }

    private fun validateUpdateTarget(expression: JsExpression, operator: JsToken) {
        if (expression !is JsExpression.Identifier && expression !is JsExpression.Member) throw error(operator, "invalid-update-target", "Invalid update target")
    }

    private fun match(vararg types: JsTokenType): Boolean {
        types.forEach { type -> if (check(type)) { advance(); return true } }
        return false
    }

    private val PROPERTY_NAME_KEYWORDS = setOf(
        JsTokenType.LET, JsTokenType.CONST, JsTokenType.VAR, JsTokenType.FUNCTION, JsTokenType.RETURN,
        JsTokenType.IF, JsTokenType.ELSE, JsTokenType.WHILE, JsTokenType.FOR, JsTokenType.OF,
        JsTokenType.TRUE, JsTokenType.FALSE, JsTokenType.NULL, JsTokenType.UNDEFINED,
        JsTokenType.BREAK, JsTokenType.CONTINUE, JsTokenType.TRY, JsTokenType.CATCH,
        JsTokenType.FINALLY, JsTokenType.THROW, JsTokenType.NEW, JsTokenType.TYPEOF
    )

    private fun consumePropertyName(message: String): JsToken {
        val token = peek()
        if (token.type == JsTokenType.IDENTIFIER || token.type in PROPERTY_NAME_KEYWORDS) return advance()
        throw error(token, "expected-property-name", message)
    }

    private fun consume(type: JsTokenType, message: String): JsToken {
        if (check(type)) return advance()
        throw error(peek(), "unexpected-token", message)
    }

    private fun check(type: JsTokenType): Boolean = if (isAtEnd()) type == JsTokenType.EOF else peek().type == type
    private fun checkNext(type: JsTokenType): Boolean = current + 1 < tokens.size && tokens[current + 1].type == type
    private fun advance(): JsToken { if (!isAtEnd()) current++; return previous() }
    private fun isAtEnd(): Boolean = peek().type == JsTokenType.EOF
    private fun peek(): JsToken = tokens[current.coerceAtMost(tokens.lastIndex)]
    private fun previous(): JsToken = tokens[(current - 1).coerceAtLeast(0)]
    private fun previousOrPeek(): JsToken = if (current > 0) previous() else peek()

    private fun synchronize() {
        if (!isAtEnd()) advance()
        while (!isAtEnd()) {
            if (previous().type == JsTokenType.SEMICOLON) return
            when (peek().type) {
                JsTokenType.FUNCTION, JsTokenType.LET, JsTokenType.CONST, JsTokenType.VAR,
                JsTokenType.IF, JsTokenType.WHILE, JsTokenType.FOR, JsTokenType.RETURN,
                JsTokenType.TRY, JsTokenType.THROW, JsTokenType.BREAK, JsTokenType.CONTINUE, JsTokenType.RIGHT_BRACE -> return
                else -> advance()
            }
        }
    }

    private fun error(token: JsToken, code: String, message: String): ParseFailure = ParseFailure(JsIssue(code, message, token.span))
    private fun issue(code: String, message: String, span: JsSourceSpan) { issues += JsIssue(code, message, span) }
    private fun spanFrom(start: JsToken, end: JsSourceSpan): JsSourceSpan = JsSourceSpan(start.span.start, end.end)
    private fun count(node: Any) { if (node is com.mohnishraj.aether.core.js.ast.JsNode) nodeCount++ }
    private fun countStatement() { nodeCount++; statementCount++ }

    private class ParseFailure(val issue: JsIssue) : RuntimeException(issue.message)

    companion object {
        fun parse(source: String, limits: JsLimits = JsLimits()): JsParseResult {
            val lexed = JsLexer(source, limits).lex()
            return JsParser(lexed.tokens, lexed.issues, limits).parse()
        }
    }
}
