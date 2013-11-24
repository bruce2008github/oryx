/*
 * Copyright (c) 2013, Cloudera, Inc. All Rights Reserved.
 *
 * Cloudera, Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"). You may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the
 * License.
 */

package com.cloudera.oryx.common.servcomp;

import java.io.File;
import java.net.MalformedURLException;
import java.util.Iterator;
import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.typesafe.config.Config;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.oryx.common.settings.ConfigUtils;

/**
 * {@link Configuration} subclass which 'patches' the Hadoop default with a few adjustments to
 * out-of-the-box settings.
 * 
 * @author Sean Owen
 */
public final class OryxConfiguration extends Configuration {

  private static final String HADOOP_CONF_DIR_KEY = "HADOOP_CONF_DIR";
  private static final String DEFAULT_HADOOP_CONF_DIR = "/etc/hadoop/conf";

  private static final Logger log = LoggerFactory.getLogger(OryxConfiguration.class);

  public OryxConfiguration() {
    this(new Configuration());
  }

  /**
   * @param configuration base {@link Configuration} to initialize from
   */
  public OryxConfiguration(Configuration configuration) {
    super(configuration);
    Config config = ConfigUtils.getDefaultConfig();
    boolean localComputation;
    if (config.hasPath("model.local-computation")) {
      localComputation = config.getBoolean("model.local-computation");
    } else {
      log.warn("model.local is deprecated; use model.local-data and model.local-computation");
      localComputation = config.getBoolean("model.local");
    }
    if (!localComputation) {
      File hadoopConfDir = findHadoopConfDir();
      addResource(hadoopConfDir, "core-site.xml");
      addResource(hadoopConfDir, "core-default.xml");
      addResource(hadoopConfDir, "hdfs-default.xml");
      addResource(hadoopConfDir, "hdfs-site.xml");
      addResource(hadoopConfDir, "mapred-default.xml");
      addResource(hadoopConfDir, "mapred-site.xml");
      addResource(hadoopConfDir, "yarn-default.xml");
      addResource(hadoopConfDir, "yarn-site.xml");

      String fsDefaultFS = get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY);
      if (fsDefaultFS == null || fsDefaultFS.equals(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_DEFAULT)) {
        // Standard config generated by older CDH 4.x seemed to set fs.default.name instead of fs.defaultFS?
        set(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, get("fs.default.name"));
        fsDefaultFS = get(CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY);
      }
      log.info("{} = {}", CommonConfigurationKeysPublic.FS_DEFAULT_NAME_KEY, fsDefaultFS);

      fixLzoCodecIssue();
    }
  }

  private void addResource(File hadoopConfDir, String fileName) {
    File file = new File(hadoopConfDir, fileName);
    if (!file.exists()) {
      return;
    }
    try {
      addResource(file.toURI().toURL());
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static File findHadoopConfDir() {
    String hadoopConfPath = System.getenv(HADOOP_CONF_DIR_KEY);
    if (hadoopConfPath == null) {
      hadoopConfPath = DEFAULT_HADOOP_CONF_DIR;
    }
    File hadoopConfDir = new File(hadoopConfPath);
    Preconditions.checkState(hadoopConfDir.exists() && hadoopConfDir.isDirectory(),
                             "Not a directory: %s", hadoopConfDir);
    return hadoopConfDir;
  }

  /**
   * Removes {@code LzoCodec} and {@code LzopCodec} from key {@code io.compression.codecs}.
   * Implementations aren't shipped with Hadoop, but are in some cases instantiated anyway even when unused.
   * So, try to erase them.
   */
  private void fixLzoCodecIssue() {
    String codecsProperty = get("io.compression.codecs");
    if (codecsProperty != null && codecsProperty.contains(".lzo.Lzo")) {
      List<String> codecs = Lists.newArrayList(Splitter.on(',').split(codecsProperty));
      for (Iterator<String> it = codecs.iterator(); it.hasNext(); ) {
        if (it.next().contains(".lzo.Lzo")) {
          it.remove();
        }
      }
      set("io.compression.codecs", Joiner.on(',').join(codecs));
    }
  }

}
