import { Link } from "react-router";
import { jt, t } from "ttag";

import { useModalOpen } from "metabase/common/hooks/use-modal-open";
import { useSelector } from "metabase/lib/redux";
import { getDocsUrl } from "metabase/selectors/settings";
import { Button, Flex, Modal, Text } from "metabase/ui";

export const LegacyPermissionsModal = ({
  isOpen,
  onClose,
}: {
  isOpen: boolean;
  onClose: () => void;
}) => {
  //Used to animate the modal
  const { open: showModal } = useModalOpen();

  const docsUrl = useSelector((state) =>
    getDocsUrl(state, { page: "permissions/no-self-service-deprecation" }),
  );
  return (
    <Modal
      opened={isOpen && showModal}
      title={t`Your data permissions may look different, but the access hasn’t changed.`}
      onClose={onClose}
      size="35rem"
      closeOnClickOutside={false}
      padding="2.5rem"
      withCloseButton={false}
      styles={{
        body: {
          paddingTop: "2.5rem",
        },
      }}
    >
      <Text mb="1rem">
        {jt`In Metabase 50, we split our data permissions into two new settings: ${(
          <Text
            key="view-data"
            component="span"
            c="brand"
            fw="bold"
          >{t`View data`}</Text>
        )} and ${(
          <Text
            key="create-queries"
            component="span"
            c="brand"
            fw="bold"
          >{t`Create queries`}</Text>
        )}. Having separate settings for what people can view and what they can query makes data permissions more expressive and easier to reason about.`}
      </Text>
      <Text mb="1.5rem">
        {t`Your permissions have been automatically converted to the new settings, with no change in data access for your groups.`}
      </Text>
      <Flex justify="space-between">
        <Button
          variant="subtle"
          p={0}
          component={Link}
          to={docsUrl}
          target="_blank"
        >
          {t`Learn more`}
        </Button>
        <Button onClick={onClose} variant="filled">
          {t`Got it`}
        </Button>
      </Flex>
    </Modal>
  );
};
