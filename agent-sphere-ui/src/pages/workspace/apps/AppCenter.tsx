import { AppstoreOutlined, ProjectOutlined } from '@ant-design/icons';
import { App, Button, Drawer, Empty, Space, Spin, Tag } from 'antd';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  APPLICATION_OUTPUT_LOCALE_LABEL_KEYS,
  APPLICATION_RUN_MODE,
  APPLICATION_RUN_MODE_LABEL_KEYS,
  APPLICATION_RUN_STATUS,
  APPLICATION_RUN_STATUS_LABEL_KEYS,
  APPLICATION_SCENE_TYPE,
  APPLICATION_SCENE_TYPE_LABEL_KEYS,
  APPLICATION_TRIGGER_TYPE,
  APPLICATION_TYPE,
  APPLICATION_TYPE_LABEL_KEYS,
  ENTITY_CONVERSATION_PURPOSE,
  ENTITY_TYPE,
  SYSTEM_PROJECTS_APP_KEY,
} from '@/constants/application';
import {
  type ApplicationRunVO,
  type ApplicationSceneVO,
  type ApplicationVO,
  applicationApi,
} from '@/services/agentSphere/application';
import { imApi } from '@/services/agentSphere/im';
import { ConversationPane } from '../chat/ConversationPane';
import { useImStream } from '../chat/ImStreamProvider';
import { tw } from '../i18n';
import { enumLabel } from './labels';
import { MemberManagerModal } from './MemberManagerModal';
import { ProjectWorkspace } from './projects/ProjectWorkspace';
import { SchemaRenderer } from './renderer/SchemaRenderer';
import { useAppStyles } from './style';

export function AppCenter({
  initialAppKey,
  initialAppId,
  initialSceneKey,
  initialSceneId,
  initialProjectId,
  initialRequirementId,
}: {
  initialAppKey?: string;
  initialAppId?: number;
  initialSceneKey?: string;
  initialSceneId?: number;
  initialProjectId?: number;
  initialRequirementId?: number;
}) {
  const { styles } = useAppStyles();
  const { message } = App.useApp();
  const [apps, setApps] = useState<ApplicationVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [activeApp, setActiveApp] = useState<ApplicationVO | null>(null);
  const deepLinkHandled = useRef(false);

  const load = () => {
    setLoading(true);
    setError(false);
    applicationApi
      .page({ page: 1, pageSize: 100 })
      .then((page) => setApps(page.records || []))
      .catch(() => {
        setError(true);
        message.error(tw('apps.loadFailed', 'Failed to load apps'));
      })
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  useEffect(() => {
    deepLinkHandled.current = false;
  }, [initialAppKey, initialAppId, initialSceneKey, initialSceneId]);

  useEffect(() => {
    if (loading || deepLinkHandled.current) return;
    const target = initialAppId
      ? apps.find((app) => app.id === initialAppId)
      : initialAppKey
        ? apps.find((app) => app.appKey === initialAppKey)
        : null;
    deepLinkHandled.current = true;
    if (target) {
      setActiveApp(target);
    }
  }, [apps, initialAppId, initialAppKey, loading]);

  const systemApps = useMemo(
    () => apps.filter((app) => app.appType === APPLICATION_TYPE.SYSTEM),
    [apps],
  );
  const customApps = useMemo(
    () => apps.filter((app) => app.appType !== APPLICATION_TYPE.SYSTEM),
    [apps],
  );

  if (activeApp) {
    if (activeApp.appKey === SYSTEM_PROJECTS_APP_KEY) {
      return (
        <ProjectWorkspace
          initialProjectId={initialProjectId}
          initialRequirementId={initialRequirementId}
          onBack={() => setActiveApp(null)}
        />
      );
    }
    return (
      <AppDetail
        app={activeApp}
        initialSceneKey={initialSceneKey}
        initialSceneId={initialSceneId}
        onBack={() => setActiveApp(null)}
      />
    );
  }

  return (
    <div className={styles.shell}>
      <div className={styles.pageHead}>
        <div>
          <div className={styles.title}>{tw('apps.title', 'App Center')}</div>
          <div className={styles.subtitle}>
            {tw('apps.subtitle', 'Digital employee workspaces')}
          </div>
        </div>
      </div>

      {loading ? (
        <div className={styles.empty}>
          <Spin tip={tw('apps.loading', 'Loading apps')} />
        </div>
      ) : error ? (
        <div className={styles.empty}>
          <Empty description={tw('apps.loadFailed', 'Failed to load apps')}>
            <Button type="primary" onClick={load}>
              {tw('apps.retry', 'Retry')}
            </Button>
          </Empty>
        </div>
      ) : (
        <>
          <div className={styles.sectionLabel}>
            {tw('apps.system', 'System apps')}
          </div>
          <div className={styles.cardGrid}>
            {systemApps.length === 0 ? (
              <Empty description={tw('apps.noApps', 'No apps available')} />
            ) : (
              systemApps.map((app) => (
                <AppCard
                  key={app.id}
                  app={app}
                  onOpen={() => setActiveApp(app)}
                />
              ))
            )}
          </div>

          <div className={styles.sectionLabel}>
            {tw('apps.industry', 'Industry apps')}
          </div>
          <div className={styles.cardGrid}>
            {customApps.length === 0 ? (
              <Empty description={tw('apps.noApps', 'No apps available')} />
            ) : (
              customApps.map((app) => (
                <AppCard
                  key={app.id}
                  app={app}
                  onOpen={() => setActiveApp(app)}
                />
              ))
            )}
          </div>
        </>
      )}
    </div>
  );
}

function AppCard({ app, onOpen }: { app: ApplicationVO; onOpen: () => void }) {
  const { styles } = useAppStyles();
  const isSystem = app.appType === APPLICATION_TYPE.SYSTEM;
  return (
    <div className={styles.card} onClick={onOpen}>
      <div className={styles.cardTop}>
        <div className={styles.cardTitle}>
          {isSystem ? <ProjectOutlined /> : <AppstoreOutlined />}
          <span>{app.name || app.nameKey}</span>
        </div>
        <Tag color={isSystem ? 'blue' : 'default'}>
          {enumLabel(APPLICATION_TYPE_LABEL_KEYS, app.appType)}
        </Tag>
      </div>
      <div className={styles.cardDesc}>
        {app.description ||
          (isSystem
            ? tw(
                'apps.projectManagementDesc',
                'Projects, requirements, members and project agents',
              )
            : tw('apps.comingSoon', 'This app will be available soon.'))}
      </div>
      <div className={styles.cardActions}>
        <Button type="link">
          {isSystem
            ? tw('apps.openProjectManagement', 'Open project management')
            : tw('apps.enter', 'Open app')}
        </Button>
      </div>
    </div>
  );
}

function AppDetail({
  app,
  initialSceneKey,
  initialSceneId,
  onBack,
}: {
  app: ApplicationVO;
  initialSceneKey?: string;
  initialSceneId?: number;
  onBack: () => void;
}) {
  const { styles } = useAppStyles();
  const { message } = App.useApp();
  const { event: streamEvent } = useImStream();
  const [scenes, setScenes] = useState<ApplicationSceneVO[]>([]);
  const [latestRun, setLatestRun] = useState<ApplicationRunVO | null>(null);
  const [runLoading, setRunLoading] = useState(false);
  const [qaOpen, setQaOpen] = useState(false);
  const [qaScene, setQaScene] = useState<ApplicationSceneVO | null>(null);
  const [qaDraft, setQaDraft] = useState<string | undefined>(undefined);
  const [qaConversationId, setQaConversationId] = useState<number | null>(null);
  const [qaRefresh, setQaRefresh] = useState(0);
  const [pageOpen, setPageOpen] = useState(false);
  const [pageScene, setPageScene] = useState<ApplicationSceneVO | null>(null);
  const [pageSchema, setPageSchema] = useState<any>(null);
  const [membersOpen, setMembersOpen] = useState(false);
  const sceneHandled = useRef(false);

  useEffect(() => {
    applicationApi
      .scenes(app.id)
      .then(setScenes)
      .catch(() => setScenes([]));
  }, [app.id]);

  const openSceneQa = async (scene: ApplicationSceneVO, draft?: string) => {
    try {
      const conversation = await imApi.ensureEntityConversation({
        bizType: ENTITY_TYPE.APPLICATION_SCENE,
        bizId: scene.id,
        bizPurpose: ENTITY_CONVERSATION_PURPOSE.CHAT,
      });
      setQaScene(scene);
      setQaDraft(draft);
      setQaConversationId(conversation.id);
      setQaOpen(true);
      setQaRefresh((value) => value + 1);
    } catch {
      message.error(tw('apps.runFailed', 'Failed to open Q&A'));
    }
  };

  const openPage = (scene: ApplicationSceneVO) => {
    try {
      const config = scene.config ? JSON.parse(scene.config) : {};
      setPageScene(scene);
      setPageSchema(config.schema || config);
      setPageOpen(true);
    } catch {
      message.error(tw('apps.pageFailed', 'Invalid page schema'));
    }
  };

  useEffect(() => {
    if (sceneHandled.current || scenes.length === 0) return;
    const target = initialSceneId
      ? scenes.find((scene) => scene.id === initialSceneId)
      : initialSceneKey
        ? scenes.find((scene) => scene.sceneKey === initialSceneKey)
        : null;
    sceneHandled.current = true;
    if (!target) return;
    if (target.sceneType === APPLICATION_SCENE_TYPE.QA) {
      void openSceneQa(target);
    } else if (target.sceneType === APPLICATION_SCENE_TYPE.PAGE) {
      openPage(target);
    }
  }, [initialSceneId, initialSceneKey, scenes]);

  const triggerRun = async (scene: ApplicationSceneVO) => {
    setRunLoading(true);
    try {
      const run = await applicationApi.triggerRun(scene.id, {
        triggerType: APPLICATION_TRIGGER_TYPE.MANUAL,
        runMode: APPLICATION_RUN_MODE.READ_ONLY,
      });
      setLatestRun(run);
      message.success(
        tw('apps.runTriggered', 'Run status: {status}', {
          status: enumLabel(APPLICATION_RUN_STATUS_LABEL_KEYS, run.status),
        }),
      );
    } catch {
      message.error(tw('apps.runFailed', 'Failed to run scene'));
    } finally {
      setRunLoading(false);
    }
  };

  const resumeRun = async () => {
    if (!latestRun) return;
    setRunLoading(true);
    try {
      setLatestRun(await applicationApi.resumeRun(latestRun.id));
    } catch {
      message.error(tw('apps.runFailed', 'Failed to resume run'));
    } finally {
      setRunLoading(false);
    }
  };

  return (
    <div className={styles.shell}>
      <div className={styles.detailHead}>
        <Button onClick={onBack}>{tw('apps.enter', 'Back')}</Button>
        <div className={styles.detailTitle}>{app.name || app.nameKey}</div>
        <Button onClick={() => setMembersOpen(true)}>
          {tw('projects.members', 'Members')}
        </Button>
      </div>
      <div className={styles.subtitle}>{app.description}</div>
      {latestRun ? (
        <div className={styles.row}>
          <div className={styles.rowMain}>
            <div className={styles.rowTitle}>
              {tw('apps.latestRun', 'Latest run')}
              <Tag>
                {enumLabel(APPLICATION_RUN_STATUS_LABEL_KEYS, latestRun.status)}
              </Tag>
              {latestRun.attempt ? <Tag>{`#${latestRun.attempt}`}</Tag> : null}
            </div>
            <div className={styles.rowDesc}>
              {enumLabel(APPLICATION_RUN_MODE_LABEL_KEYS, latestRun.runMode)} ·{' '}
              {enumLabel(
                APPLICATION_OUTPUT_LOCALE_LABEL_KEYS,
                latestRun.outputLocale,
              )}
            </div>
          </div>
          {latestRun.status === APPLICATION_RUN_STATUS.WAITING_APPROVAL ? (
            <Button loading={runLoading} onClick={() => void resumeRun()}>
              {tw('apps.resumeRun', 'Resume')}
            </Button>
          ) : null}
        </div>
      ) : null}
      <div className={styles.list}>
        {scenes.length === 0 ? (
          <Empty description={tw('apps.comingSoon', 'Coming soon')} />
        ) : (
          scenes.map((scene) => (
            <div className={styles.row} key={scene.id}>
              <div className={styles.rowMain}>
                <div className={styles.rowTitle}>
                  {scene.name || scene.nameKey}
                  <Tag>
                    {enumLabel(
                      APPLICATION_SCENE_TYPE_LABEL_KEYS,
                      scene.sceneType,
                    )}
                  </Tag>
                </div>
                <div className={styles.rowDesc}>{scene.description}</div>
                {scene.welcome ? (
                  <div className={styles.rowDesc}>{scene.welcome}</div>
                ) : null}
              </div>
              {scene.sceneType === APPLICATION_SCENE_TYPE.QA ? (
                <Space>
                  <Button onClick={() => void openSceneQa(scene)}>
                    {tw('apps.ask', 'Ask')}
                  </Button>
                </Space>
              ) : null}
              {scene.sceneType === APPLICATION_SCENE_TYPE.PAGE ? (
                <Space>
                  <Button onClick={() => openPage(scene)}>
                    {tw('apps.openPage', 'Open')}
                  </Button>
                </Space>
              ) : null}
              {scene.sceneType === APPLICATION_SCENE_TYPE.PERIODIC ||
              scene.sceneType === APPLICATION_SCENE_TYPE.ACTION ? (
                <Space>
                  <Button
                    loading={runLoading}
                    onClick={() => void triggerRun(scene)}
                  >
                    {tw('apps.runScene', 'Run')}
                  </Button>
                </Space>
              ) : null}
            </div>
          ))
        )}
      </div>

      <Drawer
        open={qaOpen}
        width={760}
        title={qaScene?.name || app.name || app.nameKey}
        onClose={() => {
          setQaOpen(false);
          setQaDraft(undefined);
        }}
        destroyOnHidden
      >
        {qaScene?.welcome ? (
          <div style={{ marginBottom: 12, color: '#6f6a86' }}>
            {qaScene.welcome}
          </div>
        ) : null}
        {qaScene?.suggestions?.length ? (
          <Space wrap style={{ marginBottom: 12 }}>
            {qaScene.suggestions.map((suggestion) => (
              <Button
                key={suggestion}
                size="small"
                onClick={() => setQaDraft(suggestion)}
              >
                {suggestion}
              </Button>
            ))}
          </Space>
        ) : null}
        {qaConversationId ? (
          <ConversationPane
            conversationId={qaConversationId}
            refreshKey={qaRefresh}
            streamEvent={streamEvent}
            initialDraft={qaDraft}
            onChanged={() => setQaRefresh((value) => value + 1)}
          />
        ) : null}
      </Drawer>

      <Drawer
        open={pageOpen}
        width={900}
        title={pageScene?.name || app.name || app.nameKey}
        onClose={() => setPageOpen(false)}
        destroyOnHidden
      >
        {pageScene?.welcome ? (
          <div style={{ marginBottom: 12, color: '#6f6a86' }}>
            {pageScene.welcome}
          </div>
        ) : null}
        <SchemaRenderer schema={pageSchema} />
        {pageScene ? (
          <Button
            style={{ marginTop: 16 }}
            onClick={() => void openSceneQa(pageScene)}
          >
            {tw('apps.askAboutPage', 'Ask about this page')}
          </Button>
        ) : null}
      </Drawer>

      <MemberManagerModal
        open={membersOpen}
        title={`${app.name || app.nameKey} · ${tw('projects.members', 'Members')}`}
        load={() => applicationApi.members(app.id)}
        add={(userId) => applicationApi.addMember(app.id, userId)}
        remove={(userId) => applicationApi.removeMember(app.id, userId)}
        onClose={() => setMembersOpen(false)}
      />
    </div>
  );
}
