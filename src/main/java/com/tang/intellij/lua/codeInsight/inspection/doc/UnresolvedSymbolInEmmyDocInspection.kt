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

package com.tang.intellij.lua.codeInsight.inspection.doc

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.tang.intellij.lua.comment.psi.LuaDocParamDef
import com.tang.intellij.lua.comment.psi.LuaDocVisitor

class UnresolvedSymbolInEmmyDocInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : LuaDocVisitor() {
            override fun visitParamDef(o: LuaDocParamDef) {
                o.paramNameRef?.let { paramNameRef ->
                    if (paramNameRef.reference.resolve() == null) {
                        holder.registerProblem(paramNameRef,
                                "Cant resolve symbol '${paramNameRef.text}'",
                                ProblemHighlightType.WEAK_WARNING)
                    }
                }
            }
        }
    }
}