// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl.search;

import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.TextOccurenceProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.ConcurrencyUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.StringSearcher;
import gnu.trove.TIntArrayList;
import gnu.trove.TIntProcedure;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

public class LowLevelSearchUtil {
  private static final Logger LOG = Logger.getInstance("#com.intellij.psi.impl.search.LowLevelSearchUtil");

  // TRUE/FALSE -> injected psi has been discovered and processor returned true/false;
  // null -> there were nothing injected found
  private static Boolean processInjectedFile(PsiElement element,
                                             @NotNull StringSearcher searcher,
                                             int start, @NotNull ProgressIndicator progress,
                                             InjectedLanguageManager injectedLanguageManager,
                                             @NotNull TextOccurenceProcessor processor) {
    if (!(element instanceof PsiLanguageInjectionHost)) return null;
    if (injectedLanguageManager == null) return null;
    List<Pair<PsiElement, TextRange>> list = injectedLanguageManager.getInjectedPsiFiles(element);
    if (list == null) return null;
    boolean hasMatchedRange = false;
    for (Pair<PsiElement, TextRange> pair : list) {
      if (!pair.second.containsRange(start, start + searcher.getPatternLength())) continue;
      hasMatchedRange = true;
      final PsiElement injected = pair.getFirst();
      if (!processElementsContainingWordInElement(processor, injected, searcher, false, progress)) return Boolean.FALSE;
    }

    return hasMatchedRange ? Boolean.TRUE : null;
  }

  private static boolean processTreeUp(@NotNull Project project,
                                       @NotNull PsiElement scope,
                                       @NotNull ASTNode leafNode,
                                       int offsetInLeaf,
                                       @NotNull StringSearcher searcher,
                                       boolean processInjectedPsi,
                                       @NotNull ProgressIndicator progress,
                                       @NotNull TextOccurenceProcessor processor) {
    final int patternLength = searcher.getPatternLength();
    InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(project);
    ASTNode currentNode = leafNode;
    int currentOffset = offsetInLeaf;
    boolean contains = false;
    ASTNode prevNode = null;
    PsiElement run = null;
    while (run != scope) {
      ProgressManager.checkCanceled();
      currentOffset += prevNode == null ? 0 : prevNode.getStartOffsetInParent();
      prevNode = currentNode;
      run = currentNode.getPsi();
      if (!contains) contains = run.getTextLength() - currentOffset >= patternLength;  //do not compute if already contains
      if (contains) {
        if (processInjectedPsi) {
          Boolean result = processInjectedFile(run, searcher, currentOffset, progress, injectedLanguageManager, processor);
          if (result != null) {
            return result.booleanValue();
          }
        }
        if (!processor.execute(run, currentOffset)) {
          return false;
        }
      }
      currentNode = currentNode.getTreeParent();
      if (currentNode == null) break;
    }
    assert run == scope : "Malbuilt PSI; scopeNode: " + scope +
                          "; containingFile: " + PsiTreeUtil.getParentOfType(scope, PsiFile.class, false) +
                          "; currentNode: " + run +
                          "; isAncestor: " + PsiTreeUtil.isAncestor(scope, run, false) +
                          "; in same file: " +
                          (PsiTreeUtil.getParentOfType(scope, PsiFile.class, false) ==
                           PsiTreeUtil.getParentOfType(run, PsiFile.class, false));

    return true;
  }

  private static ASTNode findNextLeafElementAt(ASTNode scopeNode, ASTNode last, int offset) {
    int offsetR = offset;
    if (last != null) {
      offsetR -= last.getStartOffset() - scopeNode.getStartOffset() + last.getTextLength();
      while (offsetR >= 0) {
        ASTNode next = last.getTreeNext();
        if (next == null) {
          last = last.getTreeParent();
          continue;
        }
        int length = next.getTextLength();
        offsetR -= length;
        last = next;
      }
      scopeNode = last;
      offsetR += scopeNode.getTextLength();
    }
    return scopeNode.findLeafElementAt(offsetR);
  }

  public static boolean processElementsContainingWordInElement(@NotNull final TextOccurenceProcessor processor,
                                                               @NotNull final PsiElement scope,
                                                               @NotNull final StringSearcher searcher,
                                                               boolean processInjectedPsi,
                                                               @NotNull  ProgressIndicator progress) {
    int[] occurrences = getTextOccurrencesInScope(scope, searcher);
    return processElementsAtOffsets(scope, searcher, processInjectedPsi, progress, occurrences, processor);
  }

  @NotNull
  static int[] getTextOccurrencesInScope(@NotNull PsiElement scope, @NotNull StringSearcher searcher) {
    ProgressManager.checkCanceled();

    PsiFile file = scope.getContainingFile();
    FileViewProvider viewProvider = file.getViewProvider();
    final CharSequence buffer = viewProvider.getContents();

    TextRange range = scope.getTextRange();
    if (range == null) {
      LOG.error("Element " + scope + " of class " + scope.getClass() + " has null range");
      return ArrayUtilRt.EMPTY_INT_ARRAY;
    }

    int startOffset = range.getStartOffset();
    int endOffset = range.getEndOffset();
    if (endOffset > buffer.length()) {
      diagnoseInvalidRange(scope, file, viewProvider, buffer, range);
      return ArrayUtilRt.EMPTY_INT_ARRAY;
    }

    int[] offsets = getTextOccurrences(buffer, startOffset, endOffset, searcher);
    for (int i = 0; i < offsets.length; i++) {
      offsets[i] -= startOffset;
    }
    return offsets;
  }

  static boolean processElementsAtOffsets(@NotNull PsiElement scope,
                                          @NotNull StringSearcher searcher,
                                          boolean processInjectedPsi,
                                          @NotNull ProgressIndicator progress,
                                          int[] offsetsInScope, @NotNull TextOccurenceProcessor processor) {
    if (offsetsInScope.length == 0) return true;

    final Project project = scope.getProject();
    final ASTNode scopeNode = scope.getNode();
    if (scopeNode == null) {
      throw new IllegalArgumentException(
        "Scope doesn't have node, can't scan: " + scope + "; containingFile: " + scope.getContainingFile()
      );
    }
    final int scopeStartOffset = scope.getTextRange().getStartOffset();

    // helps to avoid full tree rescan in subsequent com.intellij.lang.ASTNode#findLeafElementAt calls (O(n) instead of O(n^2))
    ASTNode lastElement = null;
    for (int offset : offsetsInScope) {
      progress.checkCanceled();
      final ASTNode leafNode = findNextLeafElementAt(scopeNode, lastElement, offset);
      if (leafNode == null) {
        LOG.error("Cannot find leaf: scope=" + scope + "; offset=" + offset + "; lastElement=" + lastElement);
        continue;
      }
      final int offsetInLeaf = offset - leafNode.getStartOffset() + scopeStartOffset;
      if (offsetInLeaf < 0) {
        throw new AssertionError("offset=" + offset + "; scopeStartOffset=" + scopeStartOffset + "; scope=" + scope);
      }
      if (!processTreeUp(project, scope, leafNode, offsetInLeaf, searcher, processInjectedPsi, progress, processor)) {
        return false;
      }
      lastElement = leafNode;
    }
    return true;
  }

  private static void diagnoseInvalidRange(@NotNull PsiElement scope,
                                           PsiFile file,
                                           FileViewProvider viewProvider,
                                           CharSequence buffer,
                                           TextRange range) {
    String msg = "Range for element: '" + scope + "' = " + range + " is out of file '" + file + "' range: " + file.getTextRange();
    msg += "; file contents length: " + buffer.length();
    msg += "\n file provider: " + viewProvider;
    Document document = viewProvider.getDocument();
    if (document != null) {
      msg += "\n committed=" + PsiDocumentManager.getInstance(file.getProject()).isCommitted(document);
    }
    for (Language language : viewProvider.getLanguages()) {
      final PsiFile root = viewProvider.getPsi(language);
      //noinspection StringConcatenationInLoop
      msg += "\n root " + language + " length=" + root.getTextLength() + (root instanceof PsiFileImpl
                                                                          ? "; contentsLoaded=" + ((PsiFileImpl)root).isContentsLoaded() : "");
    }

    LOG.error(msg);
  }

  // map (text to be scanned -> list of cached pairs of (searcher used to scan text, occurrences found))
  // occurrences found is an int array of (startOffset used, endOffset used, occurrence 1 offset, occurrence 2 offset,...)
  private static final ConcurrentMap<CharSequence, Map<StringSearcher, int[]>> cache = ContainerUtil.createConcurrentWeakMap(ContainerUtil.identityStrategy());
  public static boolean processTextOccurrences(@NotNull CharSequence text,
                                               int startOffset,
                                               int endOffset,
                                               @NotNull StringSearcher searcher,
                                               @Nullable ProgressIndicator progress,
                                               @NotNull TIntProcedure processor) {
    for (int offset : getTextOccurrences(text, startOffset, endOffset, searcher)) {
      if (!processor.execute(offset)) {
        return false;
      }
    }
    return true;
  }

  @NotNull
  private static int[] getTextOccurrences(@NotNull CharSequence text,
                                          int startOffset,
                                          int endOffset,
                                          @NotNull StringSearcher searcher) {
    if (endOffset > text.length()) {
      throw new IllegalArgumentException("end: " + endOffset + " > length: "+text.length());
    }
    Map<StringSearcher, int[]> cachedMap = cache.get(text);
    int[] cachedOccurrences = cachedMap == null ? null : cachedMap.get(searcher);
    boolean hasCachedOccurrences = cachedOccurrences != null && cachedOccurrences[0] <= startOffset && cachedOccurrences[1] >= endOffset;
    if (!hasCachedOccurrences) {
      TIntArrayList occurrences = new TIntArrayList();
      int newStart = Math.min(startOffset, cachedOccurrences == null ? startOffset : cachedOccurrences[0]);
      int newEnd = Math.max(endOffset, cachedOccurrences == null ? endOffset : cachedOccurrences[1]);
      occurrences.add(newStart);
      occurrences.add(newEnd);
      for (int index = newStart; index < newEnd; index++) {
        ProgressManager.checkCanceled();
        //noinspection AssignmentToForLoopParameter
        index = searcher.scan(text, index, newEnd);
        if (index < 0) break;
        if (checkJavaIdentifier(text, 0, text.length(), searcher, index)) {
          occurrences.add(index);
        }
      }
      cachedOccurrences = occurrences.toNativeArray();
      if (cachedMap == null) {
        cachedMap = ConcurrencyUtil.cacheOrGet(cache, text, ContainerUtil.createConcurrentSoftMap());
      }
      cachedMap.put(searcher, cachedOccurrences);
    }
    TIntArrayList offsets = new TIntArrayList(cachedOccurrences.length - 2);
    for (int i = 2; i < cachedOccurrences.length; i++) {
      int occurrence = cachedOccurrences[i];
      if (occurrence > endOffset - searcher.getPatternLength()) break;
      if (occurrence >= startOffset) {
        offsets.add(occurrence);
      }
    }
    return offsets.toNativeArray();
  }

  private static boolean checkJavaIdentifier(@NotNull CharSequence text,
                                             int startOffset,
                                             int endOffset,
                                             @NotNull StringSearcher searcher,
                                             int index) {
    if (!searcher.isJavaIdentifier()) {
      return true;
    }

    if (index > startOffset) {
      char c = text.charAt(index - 1);
      if (Character.isJavaIdentifierPart(c) && c != '$') {
        if (!searcher.isHandleEscapeSequences() || index < 2 || isEscapedBackslash(text, startOffset, index - 2)) { //escape sequence
          return false;
        }
      }
      else if (index > 0 && searcher.isHandleEscapeSequences() && !isEscapedBackslash(text, startOffset, index - 1)) {
        return false;
      }
    }

    final int patternLength = searcher.getPattern().length();
    if (index + patternLength < endOffset) {
      char c = text.charAt(index + patternLength);
      return !Character.isJavaIdentifierPart(c) || c == '$';
    }
    return true;
  }

  private static boolean isEscapedBackslash(CharSequence text, int startOffset, int index) {
    return StringUtil.isEscapedBackslash(text, startOffset, index);
  }
}
