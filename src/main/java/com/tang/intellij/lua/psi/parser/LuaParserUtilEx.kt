/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.psi.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.tang.intellij.lua.psi.LuaTypes.EXPR_LIST

internal fun expectError(builder: PsiBuilder, expectedType: IElementType, errorProvider: () -> String): Boolean {
    if (builder.tokenType === expectedType) {
        builder.advanceLexer()
        return true
    } else builder.error("${errorProvider()} expected")
    return false
}

internal fun expect(builder: PsiBuilder, expectedType: IElementType) {
    if (builder.tokenType === expectedType) {
        builder.advanceLexer()
    }
}

internal fun expectExpr(b: PsiBuilder, l: Int): PsiBuilder.Marker? {
    val expr = LuaExpressionParser.parseExpr(b, l)
    if (expr == null)
        b.error("Expression expected")
    return expr
}

internal fun expectExprList(b: PsiBuilder, l: Int): PsiBuilder.Marker? {
    val firstExpr = LuaExpressionParser.parseExprList(b, l)
    if (firstExpr != null) {
        val m = firstExpr.precede()
        m.done(EXPR_LIST)
        return m
    } else b.error("Expression expected")
    return null
}