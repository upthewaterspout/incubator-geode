/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.distributed.internal.membership.gms;


import org.apache.geode.distributed.Locator;
import org.apache.geode.distributed.internal.DistributionConfig;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.distributed.internal.membership.gms.api.MembershipConfig;
import org.apache.geode.distributed.internal.membership.gms.api.RemoteTransportConfig;
//import org.apache.geode.internal.admin.remote.RemoteTransportConfig;
import org.apache.geode.internal.net.SocketCreator;

public class ServiceConfig
    implements MembershipConfig {

  /** stall time to wait for concurrent join/leave/remove requests to be received */
  public static final long MEMBER_REQUEST_COLLECTION_INTERVAL =
      Long.getLong(DistributionConfig.GEMFIRE_PREFIX + "member-request-collection-interval", 300);

  /** in a small cluster we might want to involve all members in operations */
  public static final int SMALL_CLUSTER_SIZE = 9;

  /** various settings from Geode configuration */
  private final long joinTimeout;
  private final int[] membershipPortRange;
  private final long memberTimeout;

  private boolean isReconnecting;
  private Integer lossThreshold;
  private final Integer memberWeight;
  private boolean networkPartitionDetectionEnabled;
  private final int locatorWaitTime;

  /** the configuration for the distributed system */
  private final DistributionConfig dconfig;

  /** the transport config from the distribution manager */
  private final RemoteTransportConfig transport;


  @Override
  public boolean isReconnecting() {
    return isReconnecting;
  }

  @Override
  public int getLocatorWaitTime() {
    return locatorWaitTime;
  }


  @Override
  public long getJoinTimeout() {
    return joinTimeout;
  }


  @Override
  public int[] getMembershipPortRange() {
    return membershipPortRange;
  }


  @Override
  public long getMemberTimeout() {
    return memberTimeout;
  }


  @Override
  public int getLossThreshold() {
    return lossThreshold;
  }


  @Override
  public int getMemberWeight() {
    return memberWeight;
  }

  @Override
  public boolean isMulticastEnabled() {
    return transport.isMcastEnabled();
  }

  @Override
  public boolean isNetworkPartitionDetectionEnabled() {
    return networkPartitionDetectionEnabled;
  }

  @Override
  public boolean isUDPSecurityEnabled() {
    return !dconfig.getSecurityUDPDHAlgo().isEmpty();
  }

  @Override
  public boolean areLocatorsPreferredAsCoordinators() {
    boolean locatorsAreCoordinators;

    if (networkPartitionDetectionEnabled) {
      locatorsAreCoordinators = true;
    } else {
      // check if security is enabled
      String prop = dconfig.getSecurityPeerAuthInit();
      locatorsAreCoordinators = (prop != null && prop.length() > 0);
      if (!locatorsAreCoordinators) {
        locatorsAreCoordinators =
            Boolean.getBoolean(InternalLocator.LOCATORS_PREFERRED_AS_COORDINATORS);
      }
    }
    return locatorsAreCoordinators;
  }

  @Override
  public DistributionConfig getDistributionConfig() {
    return this.dconfig;
  }


  @Override
  public RemoteTransportConfig getTransport() {
    return this.transport;
  }


  public ServiceConfig(RemoteTransportConfig transport, DistributionConfig theConfig) {
    this.dconfig = theConfig;
    this.transport = transport;
    this.isReconnecting = (transport.getOldDSMembershipInfo() != null);

    long defaultJoinTimeout = 24000;
    if (isReconnecting || theConfig.getLocators().length() > 0 && !Locator.hasLocator()) {
      defaultJoinTimeout = 60000;
    }

    // we need to have enough time to figure out that the coordinator has crashed &
    // find a new one
    long minimumJoinTimeout = dconfig.getMemberTimeout() * 2L + MEMBER_REQUEST_COLLECTION_INTERVAL;
    if (defaultJoinTimeout < minimumJoinTimeout) {
      defaultJoinTimeout = minimumJoinTimeout;
    }

    joinTimeout = Long.getLong("p2p.joinTimeout", defaultJoinTimeout).longValue();

    // if network partition detection is enabled, we must connect to the locators
    // more frequently in order to make sure we're not isolated from them
    SocketCreator.resolve_dns = true;
    if (theConfig.getEnableNetworkPartitionDetection()) {
      if (!SocketCreator.FORCE_DNS_USE) {
        SocketCreator.resolve_dns = false;
      }
    }


    membershipPortRange = theConfig.getMembershipPortRange();

    memberTimeout = theConfig.getMemberTimeout();

    lossThreshold =
        Integer.getInteger(DistributionConfig.GEMFIRE_PREFIX + "network-partition-threshold", 51);
    if (lossThreshold < 51)
      lossThreshold = 51;
    if (lossThreshold > 100)
      lossThreshold = 100;

    memberWeight = Integer.getInteger(DistributionConfig.GEMFIRE_PREFIX + "member-weight", 0);
    locatorWaitTime = theConfig.getLocatorWaitTime();

    networkPartitionDetectionEnabled = theConfig.getEnableNetworkPartitionDetection();
  }

  @Override
  public void setIsReconnecting(boolean b) {
    this.isReconnecting = false;
  }
}
