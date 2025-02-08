/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.helpers;

import java.util.Map;

/**
 * This class is used for rename keys.
 */
public class OmRenameKeys {

  private final String volume;
  private final String bucket;
  private final Map<String, String> fromAndToKey;
  private final Map<String, OmKeyInfo> fromKeyAndToKeyInfo;

  public OmRenameKeys(String volume, String bucket,
                      Map<String, String> fromAndToKey,
                      Map<String, OmKeyInfo> fromKeyAndToKeyInfo) {
    this.volume = volume;
    this.bucket = bucket;
    this.fromAndToKey = fromAndToKey;
    this.fromKeyAndToKeyInfo = fromKeyAndToKeyInfo;
  }

  public String getVolume() {
    return volume;
  }

  public String getBucket() {
    return bucket;
  }

  public Map<String, String> getFromAndToKey() {
    return fromAndToKey;
  }

  public Map<String, OmKeyInfo> getFromKeyAndToKeyInfo() {
    return fromKeyAndToKeyInfo;
  }

}
