// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.move

import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.list.createTargetPresentationRenderer
import com.intellij.usageView.UsageInfo
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberInfo
import org.jetbrains.kotlin.idea.refactoring.memberInfo.KotlinMemberSelectionPanel
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import javax.swing.Icon
import javax.swing.JComponent

sealed interface K2MoveSource<T : KtElement> {
    val elements: Set<T>

    context(Panel)
    fun buildPanel(onError: (String?, JComponent) -> Unit)

    class FileSource(files: Set<KtFile>) : K2MoveSource<KtFile> {
        override var elements: Set<KtFile> = files
            private set

        context(Panel)
        override fun buildPanel(onError: (String?, JComponent) -> Unit) {
            val project = elements.firstOrNull()?.project ?: return

            class FileInfo(val icon: Icon?, val file: KtFile)

            val fileInfos = ActionUtil.underModalProgress(project, RefactoringBundle.message("move.title")) {
                elements.map { file -> FileInfo(file.getIcon(0), file) }
            }

            group(RefactoringBundle.message("move.files.group")) {
                lateinit var list: JBList<FileInfo>
                row {
                    list = JBList(CollectionListModel(fileInfos))
                    list.cellRenderer = createTargetPresentationRenderer { fileInfo ->
                        TargetPresentation.builder(fileInfo.file.name)
                            .icon(fileInfo.icon)
                            .locationText(fileInfo.file.virtualFile.parent.path)
                            .presentation()
                    }
                    cell(list).resizableColumn()
                }
                onApply {
                    elements = (list.model as  CollectionListModel).items.map { it.file }.toSet()
                }
            }.topGap(TopGap.NONE).bottomGap(BottomGap.NONE).resizableRow()
        }
    }

    class ElementSource(declarations: Set<KtNamedDeclaration>) : K2MoveSource<KtNamedDeclaration> {
        override var elements: Set<KtNamedDeclaration> = declarations
            private set

        fun findUsages(searchInComments: Boolean, searchForText: Boolean): List<UsageInfo> {
            return elements.flatMap { member ->
                val references = ReferencesSearch.search(member).findAll().filter { reference ->
                    reference.element.getNonStrictParentOfType<KtImportDirective>() == null
                }
                references.map { reference -> MoveRenameUsageInfo(reference, member) }
            }
        }

        context(Panel)
        override fun buildPanel(onError: (String?, JComponent) -> Unit) {
            fun getSourceFiles(elementsToMove: Collection<KtNamedDeclaration>): List<KtFile> = elementsToMove
                .map(KtPureElement::getContainingKtFile)
                .distinct()

            fun getAllDeclarations(sourceFiles: Collection<KtFile>): List<KtNamedDeclaration> = sourceFiles
                .flatMap<KtFile, KtDeclaration> { file -> if (file.isScript()) file.script!!.declarations else file.declarations }
                .filterIsInstance(KtNamedDeclaration::class.java)

            fun memberInfos(
                elementsToMove: Set<KtNamedDeclaration>,
                allDeclaration: List<KtNamedDeclaration>
            ): List<KotlinMemberInfo> = allDeclaration.map { declaration ->
                KotlinMemberInfo(declaration, false).apply {
                    isChecked = elementsToMove.contains(declaration)
                }
            }

            val project = elements.firstOrNull()?.project ?: return

            val memberInfos = ActionUtil.underModalProgress(project, RefactoringBundle.message("move.title")) {
                val sourceFiles = getSourceFiles(elements)
                val allDeclarations = getAllDeclarations(sourceFiles)
                return@underModalProgress memberInfos(elements, allDeclarations)
            }

            lateinit var memberSelectionPanel: KotlinMemberSelectionPanel
            row {
                memberSelectionPanel = cell(KotlinMemberSelectionPanel(memberInfo = memberInfos)).align(Align.FILL).component
            }.resizableRow()
            onApply {
                elements = memberSelectionPanel.table.selectedMemberInfos.map { it.member }.toSet()
            }
        }
    }

    companion object {
        fun ElementSource(file: KtFile): ElementSource {
            return ElementSource(file.declarations.filterIsInstance<KtNamedDeclaration>().toSet())
        }
    }
}