import { t } from "ttag";

import { UpsellGem } from "metabase/admin/upsells/components/UpsellGem";
import { useSetting } from "metabase/common/hooks";
import { PLUGIN_EMBEDDING, PLUGIN_EMBEDDING_SDK } from "metabase/plugins";
import { Group } from "metabase/ui";

import { EmbeddingOption } from "../EmbeddingOption";
import { LinkButton } from "../LinkButton";

import { SdkIcon } from "./SdkIcon";

export function EmbeddingSdkOptionCard() {
  const isEmbeddingSdkEnabled = useSetting("enable-embedding-sdk");
  const isEmbeddingSimpleEnabled = useSetting("enable-embedding-simple");
  const isEE = PLUGIN_EMBEDDING.isEnabled();
  const isEmbeddingSdkFeatureAvailable = PLUGIN_EMBEDDING_SDK.isEnabled();
  const isAnyEmbeddingEnabled =
    isEmbeddingSdkEnabled || isEmbeddingSimpleEnabled;

  return (
    <EmbeddingOption
      icon={<SdkIcon disabled={!isAnyEmbeddingEnabled} />}
      title={
        <Group gap="sm">
          {!isEmbeddingSdkFeatureAvailable && <UpsellGem />}
          {t`Embedded Analytics SDK`}
        </Group>
      }
      description={t`Interactive embedding with full, granular control. Embed and style individual Metabase components in your app, and tailor the experience to each person. Allows for CSS styling, custom user flows, event subscriptions, and more. Only available with SSO via JWT.`}
    >
      <Group justify="flex-start" align="center" w="100%">
        <LinkButton to={"/admin/settings/embedding-in-other-applications/sdk"}>
          {!isEE ? t`Try it out` : t`Configure`}
        </LinkButton>
      </Group>
    </EmbeddingOption>
  );
}
