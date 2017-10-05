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

package com.tang.intellij.lua.annotator

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.comment.psi.*
import com.tang.intellij.lua.highlighting.LuaHighlightingData
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaClassMemberIndex
import com.tang.intellij.lua.ty.*
import org.luaj.vm2.Lua

/**
 * LuaAnnotator
 * Created by TangZX on 2016/11/22.
 */
class LuaAnnotator : Annotator {
    private var myHolder: AnnotationHolder? = null
    private val luaVisitor = LuaElementVisitor()
    private val docVisitor = LuaDocElementVisitor()
    private val STD_MARKER = Key.create<Boolean>("lua.std.marker")
    private var isModuleFile: Boolean = false

    override fun annotate(psiElement: PsiElement, annotationHolder: AnnotationHolder) {
        myHolder = annotationHolder
        if (psiElement is LuaDocPsiElement) {
            psiElement.accept(docVisitor)
        } else if (psiElement is LuaPsiElement) {
            val psiFile = psiElement.containingFile
            isModuleFile = if (psiFile is LuaFile) { psiFile.moduleName != null } else false
            psiElement.accept(luaVisitor)
        }
        myHolder = null
    }

    internal inner class LuaElementVisitor : LuaVisitor() {

        override fun visitUncompletedStat(o: LuaUncompletedStat) {
            myHolder!!.createErrorAnnotation(o, "Uncompleted")
        }

        override fun visitLocalFuncDef(o: LuaLocalFuncDef) {
            val name = o.nameIdentifier

            if (name != null) {
                val annotation = myHolder!!.createInfoAnnotation(name, null)
                annotation.textAttributes = LuaHighlightingData.LOCAL_VAR
            }
        }

        override fun visitLocalDef(o: LuaLocalDef) {
            val nameList = o.nameList
            if (nameList != null) {
                var child: PsiElement? = nameList.firstChild
                while (child != null) {
                    if (child is LuaNameDef) {
                        val annotation = myHolder!!.createInfoAnnotation(child, null)
                        annotation.textAttributes = LuaHighlightingData.LOCAL_VAR
                    }
                    child = child.nextSibling
                }
            }
            super.visitLocalDef(o)
        }

        override fun visitTableField(o: LuaTableField) {
            super.visitTableField(o)
            val id = o.id
            if (id != null) {
                val annotation = myHolder!!.createInfoAnnotation(id, null)
                annotation.textAttributes = LuaHighlightingData.FIELD
            }
        }

        override fun visitFuncDef(o: LuaFuncDef) {
            val name = o.nameIdentifier
            if (name != null && o.forwardDeclaration == null) {
                val annotation = myHolder!!.createInfoAnnotation(name, null)
                annotation.textAttributes = if (isModuleFile) LuaHighlightingData.INSTANCE_METHOD else LuaHighlightingData.GLOBAL_FUNCTION
            }
        }

        override fun visitClassMethodName(o: LuaClassMethodName) {
            val annotation = myHolder!!.createInfoAnnotation(o.id, null)
            if (o.dot != null) {
                annotation.textAttributes = LuaHighlightingData.STATIC_METHOD
            } else {
                annotation.textAttributes = LuaHighlightingData.INSTANCE_METHOD
            }
        }

        override fun visitParamNameDef(o: LuaParamNameDef) {
            if (o.textMatches(Constants.WORD_UNDERLINE))
                return

            val search = ReferencesSearch.search(o, o.useScope)
            if (search.findFirst() == null) {
                myHolder!!.createInfoAnnotation(o, "Unused parameter : " + o.text)
                //annotation.setTextAttributes(CodeInsightColors.WEAK_WARNING_ATTRIBUTES);
            } else {
                val annotation = myHolder!!.createInfoAnnotation(o, null)
                annotation.setTextAttributes(LuaHighlightingData.PARAMETER)
            }
        }

        override fun visitNameExpr(o: LuaNameExpr) {
            val id = o.firstChild

            val res = resolve(o, SearchContext(o.project))
            if (res != null) { //std api highlighting
                val containingFile = res.containingFile
                if (LuaFileUtil.isStdLibFile(containingFile.virtualFile, o.project)) {
                    val annotation = myHolder!!.createInfoAnnotation(o, null)
                    annotation.textAttributes = LuaHighlightingData.STD_API
                    o.putUserData(STD_MARKER, true)
                    return
                }
            }

            if (res is LuaParamNameDef) {
                val annotation = myHolder!!.createInfoAnnotation(o, null)
                annotation.textAttributes = LuaHighlightingData.PARAMETER
                checkUpValue(o)
            } else if (res is LuaFuncDef) {
                val annotation = myHolder!!.createInfoAnnotation(o, null)
                val resolvedFile = res.containingFile
                if (resolvedFile !is LuaFile || resolvedFile.moduleName == null)
                    annotation.textAttributes = LuaHighlightingData.GLOBAL_FUNCTION
            } else {
                if (id.textMatches(Constants.WORD_SELF)) {
                    val annotation = myHolder!!.createInfoAnnotation(o, null)
                    annotation.textAttributes = LuaHighlightingData.SELF
                    checkUpValue(o)
                } else if (res is LuaNameDef || res is LuaLocalFuncDef) { //Local
                    val annotation = myHolder!!.createInfoAnnotation(o, null)
                    annotation.textAttributes = LuaHighlightingData.LOCAL_VAR
                    checkUpValue(o)
                } else { // 未知的，视为Global
                    val annotation = myHolder!!.createInfoAnnotation(o, null)
                    annotation.textAttributes = if (isModuleFile) LuaHighlightingData.FIELD else LuaHighlightingData.GLOBAL_VAR
                }
            }
        }

        private fun checkUpValue(o: LuaNameExpr) {
            val upValue = isUpValue(o, SearchContext(o.project))
            if (upValue) {
                val annotation = myHolder!!.createInfoAnnotation(o, null)
                annotation.textAttributes = LuaHighlightingData.UP_VALUE
            }
        }

        override fun visitIndexExpr(o: LuaIndexExpr) {
            super.visitIndexExpr(o)
            val prefix = o.prefixExpr
            if (prefix is LuaNameExpr && prefix.getUserData(STD_MARKER) != null) {
                val annotation = myHolder!!.createInfoAnnotation(o, null)
                annotation.textAttributes = LuaHighlightingData.STD_API
                o.putUserData(STD_MARKER, true)
            } else {
                val id = o.id
                if (id != null) {
                    val annotation = myHolder!!.createInfoAnnotation(id, null)
                    if (o.parent is LuaCallExpr) {
                        if (o.colon != null) {
                            annotation.textAttributes = LuaHighlightingData.INSTANCE_METHOD
                        } else {
                            annotation.textAttributes = LuaHighlightingData.STATIC_METHOD
                        }
                    } else {
                        if (o.colon != null) {
                            myHolder!!.createErrorAnnotation(o, "Arguments expected")
                        } else {
                            annotation.setTextAttributes(LuaHighlightingData.FIELD)
                        }
                    }
                }
            }
        }

        override fun visitCallExpr(o: LuaCallExpr) {
            super.visitCallExpr(o)

            // Check if type safety is enforced
            if (!LuaSettings.instance.isEnforceTypeSafety) return

            val searchContext = SearchContext(o.project)
            val type = o.expr.guessType(searchContext)

            if (type is TyPsiFunction) {
                val givenParams = o.args.children.filterIsInstance<LuaExpr>()
                val givenTypes = givenParams.map { param -> param.guessType(searchContext) }

                // Check if there are overloads?
                if (type.signatures.isEmpty()) {
                    // Check main signature
                    if (!matchCallSignature(givenParams, givenTypes, type.mainSignature, searchContext)) {
                        annotateCall(o, givenParams, givenTypes, type.mainSignature.params, searchContext)
                    }
                } else {
                    // Check if main signature matches
                    if (matchCallSignature(givenParams, givenTypes, type.mainSignature, searchContext)) return
                    // Check if there are other matching signatures
                    for (sig in type.signatures) {
                        if (matchCallSignature(givenParams, givenTypes, sig, searchContext)) return
                    }

                    // No matching overload found
                    val signatureString = givenTypes.joinToString(", ", transform = { t -> t.displayName })
                    val errorStr = "No matching overload of type: %s(%s)"
                    myHolder!!.createErrorAnnotation(o, errorStr.format(o.firstChild.text, signatureString))
                }
            }
        }

        private fun annotateCall(call: LuaExpr, concreteParams: List<LuaExpr>, concreteTypes: List<ITy>, abstractParams: Array<LuaParamInfo>, searchContext: SearchContext) {
            // Check if number of arguments match
            if (concreteParams.size > abstractParams.size) {
                val signatureString = abstractParams.joinToString(", ", transform = { param -> param.ty.displayName })
                for (i in abstractParams.size until concreteParams.size) {
                    myHolder!!.createErrorAnnotation(concreteParams[i], "Too many arguments for type %s(%s).".format(call.firstChild.text, signatureString))
                }
            }
            else if (concreteParams.size < abstractParams.size) {
                for (i in concreteParams.size until abstractParams.size) {
                    myHolder!!.createErrorAnnotation(call.lastChild.lastChild, "Missing argument: %s: %s".format(abstractParams[i].name, abstractParams[i].ty.displayName))
                }
            }
            else {
                // Check individual arguments
                for (i in 0 until concreteParams.size) {
                    // Check if concrete param is subtype of abstract type.
                    var concreteType = concreteTypes[i]
                    val abstractType = abstractParams[i].ty

                    if (!concreteType.subTypeOf(abstractType, searchContext)) {
                        myHolder!!.createErrorAnnotation(concreteParams[i], "Type mismatch. Required: '%s' Found: '%s'".format(abstractType.displayName, concreteType.displayName))
                    }
                }
            }
        }

        // Evaluate if concrete function parameters match abstract function parameters.
        private fun matchCallSignature(concreteParams: List<LuaExpr>, concreteTypes: List<ITy>, abstractParams: IFunSignature, searchContext: SearchContext): Boolean {
            // Check if number of arguments matches
            if (concreteParams.size != abstractParams.params.size) return false

            // Check individual arguments
            for (i in 0 until concreteParams.size) {
                // Check if concrete param is subtype of abstract type.
                var concreteType = concreteTypes[i]
                val abstractType = abstractParams.params[i].ty

                if (!concreteType.subTypeOf(abstractType, searchContext)) {
                    return false
                }
            }

            return true
        }

        override fun visitAssignStat(o: LuaAssignStat) {
            super.visitAssignStat(o)

            // Only do this if type safety is enabled
            if (!LuaSettings.instance.isEnforceTypeSafety) return

            val assignees = o.varExprList.exprList
            val values = o.valueExprList?.exprList ?: listOf()
            val searchContext = SearchContext(o.project)

            // Check right number of fields/assignments
            if (assignees.size > values.size) {
                for (i in values.size until assignees.size) {
                    myHolder!!.createErrorAnnotation(assignees[i], "Missing value assignment.")
                }
            } else if (assignees.size < values.size) {
                for (i in assignees.size until values.size) {
                    myHolder!!.createErrorAnnotation(values[i], "Nothing to assign to.")
                }
            } else {
                // Try to match types for each assignment
                for (i in 0 until assignees.size) {
                    val field = assignees[i]
                    val name = field.name ?: ""
                    val value = values[i]
                    val valueType = value.guessType(searchContext)

                    // Field access
                    if (field is LuaIndexExpr) {
                        // Get owner class
                        val parent = field.guessParentType(searchContext)

                        if (parent is TyClass) {
                            val fieldType = parent.findMemberType(name, searchContext) ?: Ty.NIL

                            if (!valueType.subTypeOf(fieldType, searchContext)) {
                                myHolder!!.createErrorAnnotation(value, "Type mismatch. Required: '%s' Found: '%s'".format(fieldType, valueType))
                            }
                        }
                    } else {
                        // Local/global var assignments, only check type if there is no comment defining it
                        if (o.comment == null) {
                            val fieldType = field.guessType(searchContext)

                            if (!valueType.subTypeOf(fieldType, searchContext)) {
                                myHolder!!.createErrorAnnotation(value, "Type mismatch. Required: '%s' Found: '%s'".format(fieldType, valueType))
                            }
                        }
                    }
                }
            }
        }

        override fun visitReturnStat(o: LuaReturnStat) {
            super.visitReturnStat(o)

            // Only do this if type safety is enabled
            if (!LuaSettings.instance.isEnforceTypeSafety) return

            val function = o.parent.parent.parent
            val context = SearchContext(o.project)

            var abstractTypes: List<ITy> = listOf()
            val concreteValues = o.exprList?.exprList ?: listOf()
            var concreteTypes = concreteValues.map { expr -> expr.guessType(context) }

            if (function is LuaFuncDef) {
                // Local function
                val typeList = function.comment?.returnDef?.typeList?.tyList ?: listOf()
                abstractTypes = typeList.map { ty -> ty.getType() }
            } else if (function is LuaClassMethodDef) {
                if (function.comment?.lastChild?.text == "override") {
                    val funcClass = function.guessParentType(context)
                    val overridden = if (funcClass !is ITyClass) null else funcClass?.findSuperMember(function.name ?: "", context)
                    abstractTypes = if (overridden is LuaClassMethodDef) overridden.comment?.returnDef?.typeList?.tyList?.map { it.getType() } ?: listOf() else listOf()
                } else {
                    // Local function
                    val typeList = function.comment?.returnDef?.typeList?.tyList ?: listOf()
                    abstractTypes = typeList.map { ty -> ty.getType() }
                }
            }

            // Extend expected types with nil until the same amount as given types
            if (abstractTypes.size < concreteTypes.size) {
                abstractTypes += List(concreteTypes.size - abstractTypes.size, { Ty.NIL })
            }

            // Check number
            if (abstractTypes.size > concreteTypes.size) {
                if (concreteTypes.isEmpty()) {
                    myHolder!!.createErrorAnnotation(o.lastChild, "Type mismatch. Expected: '%s' Found: 'nil'".format(abstractTypes[0]))
                } else {
                    myHolder!!.createErrorAnnotation(o.lastChild, "Incorrect number of return values. Expected %s but found %s.".format(abstractTypes.size, concreteTypes.size))
                }
            } else {
                for (i in 0 until concreteValues.size) {
                    if (!concreteTypes[i].subTypeOf(abstractTypes[i], context)) {
                        myHolder!!.createErrorAnnotation(concreteValues[i], "Type mismatch. Expected: '%s' Found: '%s'".format(abstractTypes[i], concreteTypes[i]))
                    }
                }
            }
        }

        override fun visitFuncBody(o: LuaFuncBody) {
            super.visitFuncBody(o)

            // Only do this if type safety is enabled
            if (!LuaSettings.instance.isEnforceTypeSafety) return

            // Ignore empty functions -- Definitions
            if (o.children.size < 2) return
            if (o.children[1].children.isEmpty()) return

            // Find function definition
            val searchContext = SearchContext(o.project)
            val funcDef = PsiTreeUtil.getParentOfType(o, LuaClassMethodDef::class.java)
            val funcName = funcDef!!.name ?: ""
            val comment = funcDef.comment

            var type : ITy = Ty.NIL

            // Check for comment
            if (comment != null) {
                // Check if comment is override
                if (comment.isOverride()) {
                    // Find super type
                    val superClass = funcDef.guessClassType(searchContext)
                    val superMember = superClass?.findSuperMember(funcName, searchContext)
                    if (superMember == null) {
                        myHolder!!.createErrorAnnotation(comment, "No function '%s' to override.".format(funcName))
                    } else {
                        type = if (superMember is LuaClassMethodDef) superMember.guessReturnTypeSet(searchContext) else Ty.NIL
                    }
                } else {
                    type = comment.guessType(searchContext)
                }
            }

            // If some return type is defined, we require at least one return type
            val returns = PsiTreeUtil.findChildOfType(o, LuaReturnStat::class.java)

            if (type != Ty.NIL && returns == null) {
                myHolder!!.createErrorAnnotation(o, "Return type '%s' specified but no return values found.".format(type))
            }
        }
    }

    internal inner class LuaDocElementVisitor : LuaDocVisitor() {
        override fun visitClassDef(o: LuaDocClassDef) {
            super.visitClassDef(o)
            val annotation = myHolder!!.createInfoAnnotation(o.id, null)
            annotation.textAttributes = LuaHighlightingData.CLASS_NAME
        }

        override fun visitClassNameRef(o: LuaDocClassNameRef) {
            val annotation = myHolder!!.createInfoAnnotation(o, null)
            annotation.textAttributes = LuaHighlightingData.CLASS_REFERENCE
        }

        override fun visitFieldDef(o: LuaDocFieldDef) {
            super.visitFieldDef(o)
            val id = o.nameIdentifier
            if (id != null) {
                val annotation = myHolder!!.createInfoAnnotation(id, null)
                annotation.textAttributes = LuaHighlightingData.DOC_COMMENT_TAG_VALUE
            }
        }

        override fun visitParamNameRef(o: LuaDocParamNameRef) {
            val annotation = myHolder!!.createInfoAnnotation(o, null)
            annotation.textAttributes = LuaHighlightingData.DOC_COMMENT_TAG_VALUE
        }
    }
}
