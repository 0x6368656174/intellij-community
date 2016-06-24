/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.psi.stubsHierarchy.impl;

import com.intellij.psi.impl.java.stubs.hierarchy.IndexTree;
import com.intellij.psi.stubsHierarchy.stubs.*;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;

import static com.intellij.psi.stubsHierarchy.impl.Symbol.*;

public class StubEnter {
  private NameEnvironment myNameEnvironment;
  private Symbols mySymbols;
  private StubHierarchyConnector myStubHierarchyConnector;

  private ArrayList<ClassSymbol> uncompleted = new ArrayList<ClassSymbol>();

  StubEnter(Symbols symbols) {
    myNameEnvironment = symbols.myNameEnvironment;
    mySymbols = symbols;
    myStubHierarchyConnector = new StubHierarchyConnector(myNameEnvironment, symbols);
  }

  void unitEnter(Unit tree) {
    PackageSymbol pkg = tree.myPackageId != null ? mySymbols.enterPackage(tree.myPackageId) : mySymbols.myRootPackage;
    enter(tree.myClasses, tree.myUnitInfo, pkg, pkg.myQualifiedName);
  }

  private void enter(ClassDeclaration[] trees, UnitInfo info, Symbol owner, @Nullable QualifiedName ownerName) {
    for (ClassDeclaration tree : trees) {
      enter(tree, info, owner, ownerName);
    }
  }

  private ClassSymbol[] enter(Declaration[] trees, UnitInfo info, Symbol owner, @Nullable QualifiedName ownerName) {
    ClassSymbol[] members = new ClassSymbol[trees.length];
    int i = 0;
    for (Declaration tree : trees) {
      ClassSymbol member = enter(tree, info, owner, ownerName);
      if (member != null && member.myShortName != 0) {
        members[i++] = member;
      }
    }
    members = members.length == 0 ? ClassSymbol.EMPTY_ARRAY : Arrays.copyOf(members, i);
    Arrays.sort(members, CLASS_SYMBOL_BY_NAME_COMPARATOR);
    return members;
  }

  private ClassSymbol enter(Declaration tree, UnitInfo info, Symbol owner, QualifiedName ownerName) {
    if (tree instanceof ClassDeclaration) {
      return classEnter((ClassDeclaration)tree, info, owner, ownerName);
    }
    if (tree instanceof MemberDeclaration) {
      memberEnter((MemberDeclaration)tree, info, owner, ownerName);
      return null;
    }
    return null;
  }

  private void memberEnter(MemberDeclaration tree, UnitInfo info, Symbol owner, @Nullable QualifiedName ownerName) {
    MemberSymbol mc = new MemberSymbol(owner);
    ClassSymbol[] members = enter(tree.myDeclarations, info, mc, ownerName);
    mc.setMembers(members);
  }

  private ClassSymbol classEnter(ClassDeclaration tree, UnitInfo info, Symbol owner, @Nullable QualifiedName ownerName) {
    int flags = checkFlags(tree.mods, owner);
    if (info.getType() == IndexTree.BYTECODE) {
      flags |= IndexTree.COMPILED;
    }
    QualifiedName[] supers = tree.mySupers;
    if ((tree.mods & IndexTree.ANNOTATION) != 0) {
      supers = myNameEnvironment.annotation;
    }

    int name = tree.myName;
    QualifiedName qname = name == NamesEnumerator.NO_NAME || ownerName == null ? null
                                                                               : myNameEnvironment.qualifiedName(ownerName, name, true);
    ClassSymbol classSymbol = mySymbols.enterClass(tree.myClassAnchor, flags, name, owner, info, supers, qname);

    if (uncompleted != null) {
      uncompleted.add(classSymbol);
    }
    ClassSymbol[] members = enter(tree.myDeclarations, info, classSymbol, qname);
    classSymbol.setMembers(members);
    return classSymbol;
  }

  public void connect1() {
    for (ClassSymbol classSymbol : uncompleted) {
      classSymbol.connect(myStubHierarchyConnector);
    }
    uncompleted = new ArrayList<ClassSymbol>();
  }

  public void connect2() {
    for (ClassSymbol classSymbol : uncompleted) {
      classSymbol.connect(myStubHierarchyConnector);
    }
    uncompleted = null;
  }

  public static int checkFlags(long flags, Symbol owner) {
    int mask = 0;
    if (owner.isClass() && (owner.myOwner.isPackage() || owner.isStatic())) {
      if ((flags & (IndexTree.INTERFACE | IndexTree.ENUM | IndexTree.STATIC)) != 0 )
        mask |= IndexTree.STATIC;
    }
    return mask;
  }
}
