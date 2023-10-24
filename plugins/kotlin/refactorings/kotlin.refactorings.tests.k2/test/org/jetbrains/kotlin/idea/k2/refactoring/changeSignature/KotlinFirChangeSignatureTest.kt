// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.changeSignature

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.idea.refactoring.changeSignature.BaseKotlinChangeSignatureTest
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtExpressionCodeFragment
import org.jetbrains.kotlin.psi.KtNamedFunction

class KotlinFirChangeSignatureTest :
    BaseKotlinChangeSignatureTest<KotlinChangeInfo, KotlinParameterInfo, KotlinTypeInfo, Visibility, KotlinMethodDescriptor>() {
    override fun isFirPlugin(): Boolean {
        return true
    }

    override fun getSuffix(): String {
        return "k2"
    }

    override fun checkErrorsAfter(): Boolean {
        return false // todo
    }

    override fun doTestInvokePosition(code: String) {
        doTestTargetElement<KtNamedFunction>(code)
    }

    override fun addFullQualifier(fragment: KtExpressionCodeFragment) {
        TODO("Not yet implemented")
    }

    override fun KotlinChangeInfo.createKotlinParameter(
        name: String,
        originalType: String?,
        defaultValueForCall: KtExpression?,
        defaultValueAsDefaultParameter: Boolean,
        currentType: String?
    ): KotlinParameterInfo = KotlinParameterInfo(
        name = name,
        originalType = createParameterTypeInfo(originalType, method),
        defaultValueForCall = defaultValueForCall,
        defaultValueAsDefaultParameter = defaultValueAsDefaultParameter,
        valOrVar = defaultValOrVar(method),
        defaultValue = defaultValueForCall?.takeIf { defaultValueAsDefaultParameter },
        context = method
    ).apply {
        if (currentType != null) {
            setType(currentType)
        }
    }

    override fun createParameterTypeInfo(type: String?, ktElement: PsiElement): KotlinTypeInfo = KotlinTypeInfo(type, ktElement as KtElement)

    override fun createChangeInfo(): KotlinChangeInfo {
        val targetElement = findTargetDescriptor(KotlinChangeSignatureHandler)
        return KotlinChangeInfo(KotlinMethodDescriptor(targetElement))
    }

    override fun doRefactoring(configure: KotlinChangeInfo.() -> Unit) {
        KotlinChangeSignatureProcessor(project, createChangeInfo().apply { configure() }).run()
    }

    override fun findCallers(method: PsiMethod): LinkedHashSet<PsiMethod> {
        TODO("Not yet implemented")
    }

    override fun getIgnoreDirective(): String {
        return "// IGNORE_K2"
    }
}