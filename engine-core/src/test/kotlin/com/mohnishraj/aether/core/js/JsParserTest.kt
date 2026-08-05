package com.mohnishraj.aether.core.js

import com.mohnishraj.aether.core.js.ast.JsExpression
import com.mohnishraj.aether.core.js.ast.JsStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsParserTest {
    @Test fun parsesVariableDeclaration() {
        assertIs<JsStatement.VariableDeclaration>(JsParser.parse("let x=1;").program.statements.single())
    }
    @Test fun parsesMultipleDeclarators() {
        val declaration = assertIs<JsStatement.VariableDeclaration>(JsParser.parse("let x=1,y=2;").program.statements.single())
        assertEquals(2, declaration.declarations.size)
    }
    @Test fun rejectsConstWithoutInitializer() {
        assertTrue(JsParser.parse("const x;").hasErrors)
    }
    @Test fun parsesFunctionDeclaration() {
        val function = assertIs<JsStatement.FunctionDeclaration>(JsParser.parse("function f(a,b){return a+b;}").program.statements.single())
        assertEquals(listOf("a", "b"), function.parameters)
    }
    @Test fun parsesFunctionExpression() {
        val declaration = assertIs<JsStatement.VariableDeclaration>(JsParser.parse("let f=function(x){return x;};").program.statements.single())
        assertIs<JsExpression.FunctionExpression>(declaration.declarations.single().initializer)
    }
    @Test fun observesArithmeticPrecedence() {
        val expression = assertIs<JsStatement.Expression>(JsParser.parse("1+2*3;").program.statements.single()).expression
        val binary = assertIs<JsExpression.Binary>(expression)
        assertEquals("+", binary.operator)
        assertEquals("*", assertIs<JsExpression.Binary>(binary.right).operator)
    }
    @Test fun parsesAssignmentRightAssociatively() {
        val expression = assertIs<JsStatement.Expression>(JsParser.parse("a=b=1;").program.statements.single()).expression
        assertIs<JsExpression.Assignment>(assertIs<JsExpression.Assignment>(expression).value)
    }
    @Test fun parsesConditionalExpression() {
        assertIs<JsExpression.Conditional>(
            assertIs<JsStatement.Expression>(JsParser.parse("a?b:c;").program.statements.single()).expression
        )
    }
    @Test fun parsesMemberAndCallChains() {
        val expression = assertIs<JsStatement.Expression>(JsParser.parse("a.b[0](1);").program.statements.single()).expression
        assertIs<JsExpression.Call>(expression)
    }
    @Test fun parsesArrayHoles() {
        val array = assertIs<JsExpression.ArrayLiteral>(assertIs<JsStatement.Expression>(JsParser.parse("[1,,3];").program.statements.single()).expression)
        assertEquals(null, array.elements[1])
    }
    @Test fun parsesObjectShorthand() {
        val objectLiteral = assertIs<JsExpression.ObjectLiteral>(assertIs<JsStatement.Expression>(JsParser.parse("({x});").program.statements.single()).expression)
        assertIs<JsExpression.Identifier>(objectLiteral.properties.single().value)
    }
    @Test fun parsesIfElse() {
        assertIs<JsStatement.If>(JsParser.parse("if(true)x=1;else x=2;").program.statements.single())
    }
    @Test fun parsesWhile() {
        assertIs<JsStatement.While>(JsParser.parse("while(x<3)x++;").program.statements.single())
    }
    @Test fun parsesFor() {
        assertIs<JsStatement.For>(JsParser.parse("for(let i=0;i<3;i++)x+=i;").program.statements.single())
    }
    @Test fun acceptsAutomaticSemicolonOnNewline() {
        val result = JsParser.parse("let x=1\nx+1")
        assertFalse(result.hasErrors)
        assertEquals(2, result.program.statements.size)
    }
    @Test fun reportsInvalidAssignmentTarget() {
        assertTrue(JsParser.parse("(1+2)=3;").issues.any { it.code == "invalid-assignment-target" })
    }
    @Test fun reportsReturnOutsideFunction() {
        assertTrue(JsParser.parse("return 1;").issues.any { it.code == "return-outside-function" })
    }
    @Test fun reportsBreakOutsideLoop() {
        assertTrue(JsParser.parse("break;").issues.any { it.code == "break-outside-loop" })
    }
    @Test fun recoversAfterMalformedStatement() {
        val result = JsParser.parse("let = ; let ok=2;")
        assertTrue(result.hasErrors)
        assertTrue(result.program.statements.isNotEmpty())
    }
}
