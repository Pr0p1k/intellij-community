// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.jps.javac;

import gnu.trove.THashMap;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * @author Eugene Zhuravlev
 * Date: 17-Oct-19
 */
public abstract class ModulePath {

  public interface Builder {
    Builder add(String moduleName, File pathElement);
    ModulePath create();
  }

  public abstract Collection<? extends File> getPath();

  /**
   * @param pathElement a single module path enntry
   * @return a JPMS module name associated with the passed module path element.
   *   null value does not necessarily mean the entry cannot be treated as a JPMS module. null only
   *   means that there is no module name information stored for the file in this ModulePath object
   */
  public abstract String getModuleName(File pathElement);

  public boolean isEmpty() {
    return getPath().isEmpty();
  }

  public static final ModulePath EMPTY = new ModulePath() {
    @Override
    public Collection<? extends File> getPath() {
      return Collections.emptyList();
    }

    @Override
    public String getModuleName(File pathElement) {
      return null;
    }
  };

  public static ModulePath create(Collection<? extends File> path) {
    if (path.isEmpty()) {
      return EMPTY;
    }
    final Collection<File> files = Collections.unmodifiableCollection(path);
    return new ModulePath() {
      @Override
      public Collection<? extends File> getPath() {
        return files;
      }

      @Override
      public String getModuleName(File pathElement) {
        return null;
      }
    };
  }

  public static Builder newBuilder() {
    return new Builder() {
      private final Map<File, String> myMap = new THashMap<File, String>();
      private final Collection<File> myPath = new ArrayList<File>();

      @Override
      public Builder add(String moduleName, File pathElement) {
        myPath.add(pathElement);
        if (moduleName != null) {
          myMap.put(pathElement, moduleName);
        }
        return this;
      }

      @Override
      public ModulePath create() {
        if (myPath.isEmpty()) {
          return EMPTY;
        }
        final Collection<File> files = Collections.unmodifiableCollection(myPath);
        return new ModulePath() {
          @Override
          public Collection<? extends File> getPath() {
            return files;
          }

          @Override
          public String getModuleName(File pathElement) {
            return myMap.get(pathElement);
          }
        };
      }
    };
  }

}
