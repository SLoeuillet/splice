// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.config

import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.auth.AuthConfig
import org.lfdecentralizedtrust.splice.config.*
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.wallet.config.{
  AutoAcceptTransfersConfig,
  TransferPreapprovalConfig,
  TreasuryConfig,
  WalletSweepConfig,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.*
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, NonNegativeNumeric}

import java.nio.file.Path

case class AppInstance(
    serviceUser: String,
    // An optional separate user name for the provider to be used as its user in the wallet.
    // May be useful if the IAM provider does not allow tokens that are used both by
    // machine-to-machine services and login users - in that case, set serviceUser to the m2m
    // one, and walletUser to the login one. If not provided, the serviceUser is onboarded
    // to the wallet automatically.
    walletUser: Option[String],
    dars: Seq[Path],
)

case class ValidatorOnboardingConfig(
    svClient: SvAppClientConfig, // onboarding sponsor
    secret: String, // generated by the sponsor's SV app
)
object ValidatorOnboardingConfig {
  def hideConfidential(config: ValidatorOnboardingConfig): ValidatorOnboardingConfig = {
    val hidden = "****"
    config.copy(secret = hidden)
  }
}

final case class InitialInstalledApp(appUrl: Uri)

final case class BuyExtraTrafficConfig(
    /** target throughput in bytes per second
      *
      * The top-up trigger uses this to determine how much extra traffic to purchase each time.
      * A value of zero implies that no extra traffic will be purchased automatically and
      * the throughput will be limited to the base rate.
      */
    targetThroughput: NonNegativeNumeric[BigDecimal] =
      NonNegativeNumeric.tryCreate(BigDecimal(0)), // in bytes per second

    /** minimum interval between extra traffic purchases in seconds
      *
      * This allows validator operators to control the frequency at which the top-up trigger
      * will charge them domain fees making spends more predictable. This should be greater than the
      * polling interval of the top-up trigger.
      *
      * Note that the actual interval between top-ups might be larger due to the DSO requiring
      * a minimal top-up amount larger than `targetThroughput * minTopupInterval`.
      */
    minTopupInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(10),

    /** maximum amount of time to wait for grpc calls to complete
      *
      * This is used to set the deadline for grpc calls to the participant.
      * If the call takes longer than this, it will be cancelled and retried.
      * This is only intended for testing purposes.
      * TODO(#11501) block and unblock submissions on domain reconnect
      */
    grpcDeadline: Option[NonNegativeFiniteDuration] = None,
)

case class ValidatorDecentralizedSynchronizerConfig(
    alias: DomainAlias,
    /** An optional statically specified URL for a sequencer to use to connect to the domain.
      * By default (when a URL is not specified), the list of sequencer URLs will be read from Scan.
      */
    url: Option[String] = None,
    buyExtraTraffic: BuyExtraTrafficConfig = BuyExtraTrafficConfig(),

    /** amount of extra traffic reserved for high-priority transactions eg. topups
      *
      * Note that this will be ignored if the validator is not configured to do topups
      * i.e. the target throughput is set to zero (its default value). See: reservedTrafficO.
      */
    reservedTraffic: NonNegativeLong = NonNegativeNumeric.tryCreate(200_000L),

    /** The validator's ledger client compares its remaining traffic balance against the reserved amount
      * on every command submission. This setting controls how long the traffic balance is cached before
      * being rehydrated by querying its participant.
      */
    trafficBalanceCacheTimeToLive: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(1),
) {

  /** Converts the reservedTraffic into an Option that is set to None if the validator is not
    * configured to do top-ups in the first place
    */
  lazy val reservedTrafficO: Option[NonNegativeLong] =
    if (buyExtraTraffic.targetThroughput.value <= 0L) None else Some(reservedTraffic)
}

// Validators are responsible for establishing connections to domains and so need more information than just a `SynchronizerConfig`
case class ValidatorExtraSynchronizerConfig(
    alias: DomainAlias,
    url: String,
)

case class ValidatorSynchronizerConfig(
    global: ValidatorDecentralizedSynchronizerConfig,
    extra: Seq[ValidatorExtraSynchronizerConfig] = Seq(),
)

final case class MigrateValidatorPartyConfig(
    // The scan instance the ACS snapshot should be fetched from.
    // We don't require a BFT read as ACS commitments are a sufficient indicator
    // that something went wrong.
    scan: ScanAppClientConfig,
    // if it is not set, it will get the list of parties from the participant
    // otherwise, it will be used to filter the list of parties to migrate
    partiesToMigrate: Option[Seq[String]] = None,
)

/** The schedule is specified in cron format and "max_duration" and "retention" durations. The cron string indicates
  *      the points in time at which pruning should begin in the GMT time zone, and the maximum duration indicates how
  *      long from the start time pruning is allowed to run as long as pruning has not finished pruning up to the
  *      specified retention period.
  */
final case class ParticipantPruningConfig(
    cron: String,
    maxDuration: PositiveDurationSeconds,
    retention: PositiveDurationSeconds,
)

case class ValidatorAppBackendConfig(
    override val adminApi: CommunityAdminServerConfig = CommunityAdminServerConfig(),
    override val storage: SpliceDbConfig,
    ledgerApiUser: String,
    // The hint to be used for the validator operator's party ID
    // Must be None for SV validators, Some(hint) for non-SV validators
    validatorPartyHint: Option[String],
    // Separate user names for the validator operator to be used as users in the
    // wallet. May be useful if the IAM provider does not allow tokens that are used both by
    // machine-to-machine services and login users - in that case, set ledgerApiUser to the m2m
    // one, and validatorWalletUsers to include the login one. If not provided, the ledgerApiUser is onboarded
    // to the wallet automatically.
    validatorWalletUsers: Seq[String],
    auth: AuthConfig,
    appInstances: Map[String, AppInstance],
    participantClient: ParticipantClientConfig,
    scanClient: BftScanClientConfig,
    override val automation: AutomationConfig = AutomationConfig(),
    domains: ValidatorSynchronizerConfig,
    onboarding: Option[ValidatorOnboardingConfig],
    treasury: TreasuryConfig = TreasuryConfig(),
    participantBootstrappingDump: Option[ParticipantBootstrapDumpConfig] = None,
    participantIdentitiesBackup: Option[PeriodicBackupDumpConfig] = None,
    transferPreapproval: TransferPreapprovalConfig = TransferPreapprovalConfig(),
    // Migrate the validator party from an existing participant with the same namespace.
    migrateValidatorParty: Option[MigrateValidatorPartyConfig] = None,
    svValidator: Boolean = false,
    svUser: Option[String] = None,
    domainMigrationDumpPath: Option[Path] = None,
    restoreFromMigrationDump: Option[Path] = None,
    // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
    domainMigrationId: Long = 0L,
    prevetDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(5),
    parameters: SpliceParametersConfig = SpliceParametersConfig(),
    ingestFromParticipantBegin: Boolean = true,
    ingestUpdateHistoryFromParticipantBegin: Boolean = true,
    enableWallet: Boolean = true,
    sequencerRequestAmplificationPatience: NonNegativeFiniteDuration =
      ValidatorAppBackendConfig.DEFAULT_SEQUENCER_REQUEST_AMPLIFICATION_PATIENCE,
    /** The configuration for sweeping funds periodically to other validator's wallet
      */
    walletSweep: Map[String, WalletSweepConfig] = Map.empty,

    /** The configuration for auto-accepting transfers from other parties
      */
    autoAcceptTransfers: Map[String, AutoAcceptTransfersConfig] = Map.empty,
    // We don't make this optional to encourage users to think about it at least. They
    // can always set it to an empty string.
    contactPoint: String,
    // The rate at which acknowledgements are produced, we allow reducing this for tests with aggressive pruning intervals.
    timeTrackerMinObservationDuration: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofMinutes(1),
    // TODO(#13301) Remove this flag
    supportsSoftDomainMigrationPoc: Boolean = false,
    // Identifier for all Canton nodes controlled by this application
    cantonIdentifierConfig: Option[ValidatorCantonIdentifierConfig] = None,
    participantPruningSchedule: Option[ParticipantPruningConfig] = None,
    deduplicationDuration: PositiveDurationSeconds = PositiveDurationSeconds.ofHours(24),
) extends SpliceBackendConfig // TODO(#736): fork or generalize this trait.
    {
  override val nodeTypeName: String = "validator"

  override def clientAdminApi: ClientConfig = adminApi.clientConfig

}

object ValidatorAppBackendConfig {
  val DEFAULT_SEQUENCER_REQUEST_AMPLIFICATION_PATIENCE = NonNegativeFiniteDuration.ofSeconds(10)
}

case class ValidatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

case class AnsAppExternalClientConfig(
    adminApi: NetworkAppClientConfig,
    ledgerApiUser: String,
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

final case class ValidatorCantonIdentifierConfig(
    participant: String
)
object ValidatorCantonIdentifierConfig {
  private def default(config: ValidatorAppBackendConfig): ValidatorCantonIdentifierConfig = {
    val identifier = config.validatorPartyHint.getOrElse("unnamedValidator")
    ValidatorCantonIdentifierConfig(
      participant = identifier
    )
  }

  // The config reader/writer derivation fails if we make this a method on the config class so we keep it here on the companion
  def resolvedNodeIdentifierConfig(
      config: ValidatorAppBackendConfig
  ): ValidatorCantonIdentifierConfig =
    config.cantonIdentifierConfig.getOrElse(ValidatorCantonIdentifierConfig.default(config))
}
