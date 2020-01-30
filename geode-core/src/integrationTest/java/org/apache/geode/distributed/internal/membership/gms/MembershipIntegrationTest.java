package org.apache.geode.distributed.internal.membership.gms;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.apache.geode.distributed.internal.membership.api.MemberIdentifier;
import org.apache.geode.distributed.internal.membership.api.MemberIdentifierFactoryImpl;
import org.apache.geode.distributed.internal.membership.api.MemberIdentifierImpl;
import org.apache.geode.distributed.internal.membership.api.MemberStartupException;
import org.apache.geode.distributed.internal.membership.api.Membership;
import org.apache.geode.distributed.internal.membership.api.MembershipBuilder;
import org.apache.geode.distributed.internal.membership.api.MembershipConfig;
import org.apache.geode.distributed.internal.membership.api.MembershipConfigurationException;
import org.apache.geode.distributed.internal.membership.api.MembershipLocator;
import org.apache.geode.distributed.internal.membership.api.MembershipLocatorBuilder;
import org.apache.geode.distributed.internal.tcpserver.TcpClient;
import org.apache.geode.distributed.internal.tcpserver.TcpSocketCreator;
import org.apache.geode.internal.admin.SSLConfig;
import org.apache.geode.internal.net.SocketCreator;
import org.apache.geode.internal.serialization.DSFIDSerializer;
import org.apache.geode.internal.serialization.internal.DSFIDSerializerImpl;
import org.apache.geode.logging.internal.executors.LoggingExecutors;

public class MembershipIntegrationTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  private InetAddress localHost;
  private DSFIDSerializer dsfidSerializer;
  private TcpSocketCreator socketCreator;

  @Before
  public void before() throws IOException, MembershipConfigurationException {
    localHost = InetAddress.getLocalHost();


    this.dsfidSerializer = new DSFIDSerializerImpl();

    // TODO - stop using geode-core socket creator
    socketCreator = new SocketCreator(new SSLConfig.Builder().build());
  }

  @Test
  public void oneMembershipCanStartWithALocator()
      throws IOException, MemberStartupException {
    final MembershipLocator<MemberIdentifierImpl> locator = createLocator();
    locator.start();

    final Membership<MemberIdentifierImpl> membership = createMembership(locator,
        locator.getPort());
    start(membership);

    assertThat(membership.getView().getMembers()).hasSize(1);
  }

  @Test
  public void twoMembersCanStartWithOneLocator()
      throws IOException, MemberStartupException {
    MembershipLocator<MemberIdentifierImpl> locator = createLocator();
    locator.start();
    int locatorPort = locator.getPort();

    Membership<MemberIdentifierImpl> membership1 = createMembership(locator, locatorPort);
    start(membership1);

    Membership<MemberIdentifierImpl> membership2 = createMembership(null, locatorPort);
    start(membership2);

    assertThat(membership1.getView().getMembers()).hasSize(2);
    assertThat(membership2.getView().getMembers()).hasSize(2);
  }

  @Test
  public void twoLocatorsCanStartSequentially()
      throws IOException, MemberStartupException {

    MembershipLocator<MemberIdentifierImpl> locator1 = createLocator();
    locator1.start();
    int locatorPort1 = locator1.getPort();

    Membership<MemberIdentifierImpl> membership1 = createMembership(locator1, locatorPort1);
    start(membership1);

    MembershipLocator<MemberIdentifierImpl> locator2 = createLocator(locatorPort1);
    locator2.start();
    int locatorPort2 = locator2.getPort();

    Membership<MemberIdentifierImpl> membership2 =
        createMembership(locator2, locatorPort1, locatorPort2);
    start(membership2);

    assertThat(membership1.getView().getMembers()).hasSize(2);
    assertThat(membership2.getView().getMembers()).hasSize(2);
  }

  @Test
  public void secondMembershipCanJoinUsingATheSecondLocatorToStart()
      throws IOException, MemberStartupException {

    MembershipLocator<MemberIdentifierImpl> locator1 = createLocator();
    locator1.start();
    int locatorPort1 = locator1.getPort();

    Membership<MemberIdentifierImpl> membership1 = createMembership(locator1, locatorPort1);
    start(membership1);

    MembershipLocator<MemberIdentifierImpl> locator2 = createLocator(locatorPort1);
    locator2.start();
    int locatorPort2 = locator2.getPort();

    // Force the next membership to use locator2 by stopping locator1
    locator1.stop();

    Membership<MemberIdentifierImpl> membership2 =
        createMembership(locator2, locatorPort1, locatorPort2);
    start(membership2);

    assertThat(membership1.getView().getMembers()).hasSize(2);
    assertThat(membership2.getView().getMembers()).hasSize(2);
  }

  @Test
  public void twoLocatorsCanStartConcurrently() {

  }

  @Test
  public void oneMembershipFailsToStartWithoutRunningLocator() {

  }

  private void start(Membership<MemberIdentifierImpl> membership)
      throws MemberStartupException {
    membership.start();
    membership.startEventProcessing();
  }

  private Membership<MemberIdentifierImpl> createMembership(
      MembershipLocator<MemberIdentifierImpl> embeddedLocator, int... locatorPorts)
      throws MembershipConfigurationException {
    final boolean isALocator = embeddedLocator != null;
    MembershipConfig config = createMembershipConfig(isALocator, locatorPorts);

    MemberIdentifierFactoryImpl memberIdFactory = new MemberIdentifierFactoryImpl();

    TcpClient locatorClient = new TcpClient(socketCreator, dsfidSerializer.getObjectSerializer(),
        dsfidSerializer.getObjectDeserializer());

    return MembershipBuilder.<MemberIdentifierImpl>newMembershipBuilder(
        socketCreator, locatorClient, dsfidSerializer, memberIdFactory)
        .setMembershipLocator(embeddedLocator)
        .setConfig(config)
        .create();
  }

  private MembershipConfig createMembershipConfig(boolean isALocator, int[] locatorPorts) {
    return new MembershipConfig() {
      public String getLocators() {
        return getLocatorString(locatorPorts);
      }

      // TODO - the Membership system starting in the locator *MUST* be told that is
      // is a locator through this flag. Ideally it should be able to infer this from
      // being associated with a locator
      @Override
      public int getVmKind() {
        return isALocator ? MemberIdentifier.LOCATOR_DM_TYPE : MemberIdentifier.NORMAL_DM_TYPE;
      }
    };
  }

  private String getLocatorString(int... locatorPorts) {
    String hostName = localHost.getHostName();
    return Arrays.stream(locatorPorts)
        .mapToObj(port -> hostName + '[' + port + ']')
        .collect(Collectors.joining(","));
  }

  private MembershipLocator<MemberIdentifierImpl> createLocator(int... locatorPorts)
      throws MembershipConfigurationException,
      IOException {
    final Supplier<ExecutorService> executorServiceSupplier =
        () -> LoggingExecutors.newCachedThreadPool("membership", false);
    Path locatorDirectory = temporaryFolder.newFolder().toPath();

    MembershipConfig config = createMembershipConfig(true, locatorPorts);

    return MembershipLocatorBuilder.<MemberIdentifierImpl>newLocatorBuilder(
        socketCreator,
        dsfidSerializer,
        locatorDirectory,
        executorServiceSupplier)
        .setConfig(config)
        .create();
  }


}
