import {
  ArrowLeftOutlined,
  BarChartOutlined,
  DeleteOutlined,
  EditOutlined,
  FileAddOutlined,
  PaperClipOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  TeamOutlined,
} from '@ant-design/icons';
import {
  App,
  Button,
  Col,
  DatePicker,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Mentions,
  Modal,
  Pagination,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Tabs,
  Tag,
  Tooltip,
  Upload,
} from 'antd';
import dayjs from 'dayjs';
import { type UIEvent, useEffect, useRef, useState } from 'react';
import {
  PROJECT_STATUS_LABEL_KEYS,
  PROJECT_STATUS_META,
  REQUIREMENT_STATUS,
  REQUIREMENT_STATUS_LABEL_KEYS,
  REQUIREMENT_STATUS_LIST,
  REQUIREMENT_STATUS_META,
} from '@/constants/project';
import { agentApi } from '@/services/agentSphere/api';
import {
  type CommentMentionInput,
  type ProjectAssistantAvatarVO,
  type ProjectMemberVO,
  type ProjectVO,
  projectApi,
  projectAssistantApi,
  type RequirementAttachmentVO,
  type RequirementCommentVO,
  type RequirementVO,
} from '@/services/agentSphere/project';
import { userDisplayLabel } from '@/utils/userLabel';
import { tw } from '../../i18n';
import { enumLabel } from '../labels';
import { MemberManagerModal } from '../MemberManagerModal';
import { useAppStyles } from '../style';
import { ProjectAssistantDrawer } from './ProjectAssistantDrawer';
import { RequirementGantt } from './RequirementGantt';
import { RichTextContent } from './RichTextContent';
import { RichTextEditor } from './RichTextEditor';

const PROJECT_TAB = 'projects';
const REQUIREMENT_TAB = 'requirements';
const PROJECT_PAGE_SIZE = 12;
const LANE_PAGE_SIZE = 5;

interface RequirementLane {
  rows: RequirementVO[];
  total: number;
  page: number;
  loading: boolean;
}

export function ProjectWorkspace({
  initialProjectId,
  initialRequirementId,
  onBack,
}: {
  initialProjectId?: number;
  initialRequirementId?: number;
  onBack?: () => void;
}) {
  const { styles } = useAppStyles();
  const { message } = App.useApp();
  const [activeTab, setActiveTab] = useState<string>(PROJECT_TAB);
  const [projects, setProjects] = useState<ProjectVO[]>([]);
  const [projectOptions, setProjectOptions] = useState<ProjectVO[]>([]);
  const [projectPage, setProjectPage] = useState(1);
  const [projectPageSize, setProjectPageSize] = useState(PROJECT_PAGE_SIZE);
  const [projectTotal, setProjectTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [requirementLanes, setRequirementLanes] = useState<
    Record<string, RequirementLane>
  >({});
  const [requirementLanesLoading, setRequirementLanesLoading] = useState(false);
  const [requirementProjectFilter, setRequirementProjectFilter] = useState<
    number | undefined
  >();
  const [selectedProject, setSelectedProject] = useState<ProjectVO | null>(
    null,
  );
  const [projectMembers, setProjectMembers] = useState<ProjectMemberVO[]>([]);
  const [contacts, setContacts] = useState<any[]>([]);
  const [projectFormOpen, setProjectFormOpen] = useState(false);
  const [editingProject, setEditingProject] = useState<ProjectVO | null>(null);
  const [projectSaving, setProjectSaving] = useState(false);
  const [requirementOpen, setRequirementOpen] = useState(false);
  const [requirementCreating, setRequirementCreating] = useState(false);
  const [requirementEditOpen, setRequirementEditOpen] = useState(false);
  const [editingRequirement, setEditingRequirement] =
    useState<RequirementVO | null>(null);
  const [requirementSaving, setRequirementSaving] = useState(false);
  const [detailRequirement, setDetailRequirement] =
    useState<RequirementVO | null>(null);
  const [comments, setComments] = useState<RequirementCommentVO[]>([]);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [commentDraft, setCommentDraft] = useState('');
  const [commentAvatars, setCommentAvatars] = useState<
    ProjectAssistantAvatarVO[]
  >([]);
  const [commentAttachments, setCommentAttachments] = useState<
    RequirementAttachmentVO[]
  >([]);
  const [commentAttachmentUploading, setCommentAttachmentUploading] =
    useState(false);
  const [commentSubmitting, setCommentSubmitting] = useState(false);
  const [ganttOpen, setGanttOpen] = useState(false);
  const [ganttProject, setGanttProject] = useState<ProjectVO | null>(null);
  const [ganttRequirements, setGanttRequirements] = useState<RequirementVO[]>(
    [],
  );
  const [ganttLoading, setGanttLoading] = useState(false);
  const [detailProject, setDetailProject] = useState<ProjectVO | null>(null);
  const [projectDetailMembers, setProjectDetailMembers] = useState<
    ProjectMemberVO[]
  >([]);
  const [projectDetailRequirements, setProjectDetailRequirements] = useState<
    RequirementVO[]
  >([]);
  const [dragRequirementId, setDragRequirementId] = useState<number | null>(
    null,
  );
  const [projectForm] = Form.useForm();
  const [requirementForm] = Form.useForm();
  const [requirementEditForm] = Form.useForm();
  const [assistantOpen, setAssistantOpen] = useState(false);
  const [assistantTab, setAssistantTab] = useState('chat');
  const [assistantProject, setAssistantProject] = useState<ProjectVO | null>(
    null,
  );
  const [membersOpen, setMembersOpen] = useState(false);

  const loadProjectOptions = () => {
    projectApi
      .page({ page: 1, pageSize: 500 })
      .then((result) => setProjectOptions(result.records || []))
      .catch(() => setProjectOptions([]));
  };

  const loadProjects = (page = projectPage, pageSize = projectPageSize) => {
    setLoading(true);
    projectApi
      .page({ page, pageSize })
      .then((result) => {
        const rows = result.records || [];
        const total = result.total || 0;
        const maxPage = Math.max(1, Math.ceil(total / pageSize));
        if (rows.length === 0 && total > 0 && page > maxPage) {
          loadProjects(maxPage, pageSize);
          return;
        }
        setProjects(rows);
        setProjectTotal(total);
        setProjectPage(page);
        setProjectPageSize(pageSize);
      })
      .catch(() => message.error(tw('projects.loadFailed', 'Failed to load')))
      .finally(() => setLoading(false));
  };

  const loadRequirementLanes = (projectId = requirementProjectFilter) => {
    setRequirementLanesLoading(true);
    Promise.all(
      REQUIREMENT_STATUS_LIST.map((status) =>
        projectApi
          .requirementPage({
            projectId: projectId || undefined,
            status,
            page: 1,
            pageSize: LANE_PAGE_SIZE,
          })
          .then(
            (result) =>
              [
                status,
                {
                  rows: result.records || [],
                  total: result.total || 0,
                  page: 1,
                  loading: false,
                },
              ] as const,
          ),
      ),
    )
      .then((entries) => setRequirementLanes(Object.fromEntries(entries)))
      .catch(() => setRequirementLanes({}))
      .finally(() => setRequirementLanesLoading(false));
  };

  const loadMoreLane = (status: string) => {
    const lane = requirementLanes[status];
    if (!lane || lane.loading || lane.rows.length >= lane.total) {
      return;
    }
    const nextPage = lane.page + 1;
    setRequirementLanes((previous) => ({
      ...previous,
      [status]: { ...previous[status], loading: true },
    }));
    projectApi
      .requirementPage({
        projectId: requirementProjectFilter || undefined,
        status,
        page: nextPage,
        pageSize: LANE_PAGE_SIZE,
      })
      .then((result) => {
        setRequirementLanes((previous) => {
          const current = previous[status] || {
            rows: [],
            total: 0,
            page: 0,
            loading: false,
          };
          return {
            ...previous,
            [status]: {
              rows: [...current.rows, ...(result.records || [])],
              total: result.total || current.total,
              page: nextPage,
              loading: false,
            },
          };
        });
      })
      .catch(() =>
        setRequirementLanes((previous) => ({
          ...previous,
          [status]: { ...previous[status], loading: false },
        })),
      );
  };

  const onLaneScroll = (event: UIEvent<HTMLDivElement>, status: string) => {
    const element = event.currentTarget;
    if (element.scrollTop + element.clientHeight >= element.scrollHeight - 24) {
      loadMoreLane(status);
    }
  };

  useEffect(() => {
    loadProjects(1, PROJECT_PAGE_SIZE);
    loadProjectOptions();
    loadContacts();
  }, []);

  const loadContacts = () => {
    agentApi.workspaceContacts
      .users({ page: 1, size: 100 })
      .then((page) => setContacts(page?.records || []))
      .catch(() => setContacts([]));
  };

  const refreshProjectMembers = (projectId: number) => {
    projectApi
      .members(projectId)
      .then((rows) => setProjectMembers(rows || []))
      .catch(() => setProjectMembers([]));
  };

  const userLabel = (userId?: number, displayName?: string) => {
    if (displayName) return displayName;
    if (!userId) return tw('projects.unassigned', 'Unassigned');
    const contact = contacts.find((row) => row.id === userId);
    return contact ? userDisplayLabel(contact, `#${userId}`) : `#${userId}`;
  };

  const assigneeOptions = projectMembers.map((member) => ({
    value: member.userId,
    label: userLabel(member.userId, member.displayName),
  }));

  const refreshAssigneeOptions = (projectId?: number) => {
    if (projectId) {
      refreshProjectMembers(projectId);
    }
  };

  const projectOptionsForSelect = projectOptions.map((project) => ({
    value: project.id,
    label: project.name,
  }));

  const projectPeriod = (project: ProjectVO) => {
    const range = [project.startDate, project.endDate]
      .filter(Boolean)
      .join(' ~ ');
    return range || tw('projects.notSet', 'Not set');
  };

  const isOverdue = (requirement: RequirementVO) =>
    Boolean(
      requirement.dueDate &&
        requirement.status !== REQUIREMENT_STATUS.DONE &&
        dayjs(requirement.dueDate).isBefore(dayjs(), 'day'),
    );

  const openCreateProject = () => {
    setEditingProject(null);
    projectForm.resetFields();
    setProjectFormOpen(true);
  };

  const openEditProject = (project: ProjectVO) => {
    setEditingProject(project);
    projectForm.resetFields();
    projectForm.setFieldsValue({
      name: project.name,
      description: project.description,
      startDate: project.startDate ? dayjs(project.startDate) : undefined,
      endDate: project.endDate ? dayjs(project.endDate) : undefined,
    });
    setProjectFormOpen(true);
  };

  const saveProject = async () => {
    const values = await projectForm.validateFields();
    const payload = {
      name: values.name,
      description: values.description,
      startDate: values.startDate
        ? values.startDate.format('YYYY-MM-DD')
        : undefined,
      endDate: values.endDate ? values.endDate.format('YYYY-MM-DD') : undefined,
    };
    setProjectSaving(true);
    try {
      if (editingProject) {
        await projectApi.update(editingProject.id, payload);
        message.success(tw('projects.projectUpdated', 'Project updated'));
        setSelectedProject((current) =>
          current?.id === editingProject.id
            ? { ...current, ...payload }
            : current,
        );
        setDetailProject((current) =>
          current?.id === editingProject.id
            ? { ...current, ...payload }
            : current,
        );
      } else {
        await projectApi.create(payload);
        message.success(tw('projects.created', 'Created'));
      }
      setProjectFormOpen(false);
      setEditingProject(null);
      loadProjects(editingProject ? projectPage : 1, projectPageSize);
      loadProjectOptions();
    } catch {
      message.error(
        tw(
          editingProject ? 'projects.updateFailed' : 'projects.createFailed',
          editingProject ? 'Failed to update' : 'Failed to create',
        ),
      );
    } finally {
      setProjectSaving(false);
    }
  };

  const deleteProject = async (project: ProjectVO) => {
    try {
      await projectApi.remove(project.id);
      message.success(tw('projects.projectDeleted', 'Project deleted'));
      if (selectedProject?.id === project.id) {
        setSelectedProject(null);
      }
      if (requirementProjectFilter === project.id) {
        setRequirementProjectFilter(undefined);
        loadRequirementLanes(undefined);
      }
      loadProjects(projectPage, projectPageSize);
      loadProjectOptions();
    } catch {
      message.error(tw('projects.deleteFailed', 'Failed to delete'));
    }
  };

  const openProjectMembers = (project: ProjectVO) => {
    setSelectedProject(project);
    refreshProjectMembers(project.id);
    setMembersOpen(true);
  };

  const openProjectAssistant = (project: ProjectVO, tab = 'chat') => {
    setAssistantProject(project);
    setAssistantTab(tab);
    setAssistantOpen(true);
  };

  const openProjectGantt = (project: ProjectVO) => {
    setGanttProject(project);
    setGanttOpen(true);
    setGanttLoading(true);
    projectApi
      .requirements(project.id, { page: 1, pageSize: 500 })
      .then((result) => setGanttRequirements(result.records || []))
      .catch(() => setGanttRequirements([]))
      .finally(() => setGanttLoading(false));
  };

  const openProjectDetail = (project: ProjectVO) => {
    setDetailProject(project);
    Promise.all([
      projectApi.detail(project.id).catch(() => project),
      projectApi.members(project.id).catch(() => []),
      projectApi
        .requirements(project.id, { page: 1, pageSize: 100 })
        .then((result) => result.records || [])
        .catch(() => []),
    ]).then(([freshProject, members, requirements]) => {
      setDetailProject(freshProject || project);
      setProjectDetailMembers(members);
      setProjectDetailRequirements(requirements);
    });
  };

  const findLaneRequirement = (id: number) => {
    for (const status of REQUIREMENT_STATUS_LIST) {
      const row = requirementLanes[status]?.rows.find((item) => item.id === id);
      if (row) return row;
    }
    return undefined;
  };

  const moveRequirementToStatus = async (status: string) => {
    const id = dragRequirementId;
    setDragRequirementId(null);
    if (!id) return;
    const requirement = findLaneRequirement(id);
    if (!requirement || requirement.status === status) return;
    try {
      await projectApi.changeRequirementStatus(requirement.id, status);
      await loadRequirementLanes(requirementProjectFilter);
    } catch {
      message.error(tw('projects.updateFailed', 'Failed to update'));
    }
  };

  const moveGanttRequirement = async (
    requirement: RequirementVO,
    startDate: string,
    dueDate: string,
  ) => {
    setGanttRequirements((previous) =>
      previous.map((item) =>
        item.id === requirement.id ? { ...item, startDate, dueDate } : item,
      ),
    );
    try {
      await projectApi.updateRequirement(requirement.id, {
        startDate,
        dueDate,
      });
      message.success(tw('projects.ganttMoved', 'Requirement dates updated'));
      loadRequirementLanes(requirementProjectFilter);
    } catch {
      message.error(
        tw('projects.ganttMoveFailed', 'Failed to update requirement dates'),
      );
      if (ganttProject) {
        openProjectGantt(ganttProject);
      }
    }
  };

  const changeRequirementProject = (projectId?: number) => {
    setSelectedProject(
      projectOptions.find((project) => project.id === projectId) || null,
    );
    requirementForm.setFieldValue('assigneeUserId', undefined);
    refreshAssigneeOptions(projectId);
  };

  const openCreateRequirement = (project?: ProjectVO | null) => {
    const target =
      project ||
      projectOptions.find((item) => item.id === requirementProjectFilter) ||
      null;
    requirementForm.resetFields();
    requirementForm.setFieldsValue({ projectId: target?.id });
    setSelectedProject(target);
    if (target) {
      refreshProjectMembers(target.id);
    } else {
      setProjectMembers([]);
    }
    setRequirementOpen(true);
  };

  const createRequirement = async () => {
    const values = await requirementForm.validateFields();
    if (!values.projectId) {
      message.error(tw('projects.selectProject', 'Select project'));
      return;
    }
    setRequirementCreating(true);
    try {
      await projectApi.createRequirement(values.projectId, {
        title: values.title,
        description: values.description,
        priority: values.priority ?? undefined,
        assigneeUserId: values.assigneeUserId ?? undefined,
        startDate: values.startDate
          ? values.startDate.format('YYYY-MM-DD')
          : undefined,
        dueDate: values.dueDate
          ? values.dueDate.format('YYYY-MM-DD')
          : undefined,
      });
      message.success(tw('projects.created', 'Created'));
      setRequirementOpen(false);
      requirementForm.resetFields();
      setActiveTab(REQUIREMENT_TAB);
      loadRequirementLanes(requirementProjectFilter);
    } catch {
      message.error(tw('projects.createFailed', 'Failed to create'));
    } finally {
      setRequirementCreating(false);
    }
  };

  const openRequirementEdit = (requirement: RequirementVO) => {
    setEditingRequirement(requirement);
    refreshProjectMembers(requirement.projectId);
    requirementEditForm.resetFields();
    requirementEditForm.setFieldsValue({
      title: requirement.title,
      description: requirement.description,
      status: requirement.status,
      priority: requirement.priority,
      assigneeUserId: requirement.assigneeUserId,
      startDate: requirement.startDate
        ? dayjs(requirement.startDate)
        : undefined,
      dueDate: requirement.dueDate ? dayjs(requirement.dueDate) : undefined,
    });
    setRequirementEditOpen(true);
  };

  const loadRequirementComments = (requirementId: number) => {
    setCommentsLoading(true);
    projectApi
      .requirementComments(requirementId)
      .then((rows) => setComments(rows || []))
      .catch(() => setComments([]))
      .finally(() => setCommentsLoading(false));
  };

  const openRequirementDetail = (requirement: RequirementVO) => {
    setDetailRequirement(requirement);
    setCommentDraft('');
    setCommentAttachments([]);
    refreshProjectMembers(requirement.projectId);
    projectAssistantApi
      .avatars(requirement.projectId)
      .then((rows) => setCommentAvatars(rows || []))
      .catch(() => setCommentAvatars([]));
    loadRequirementComments(requirement.id);
  };

  const uploadCommentAttachment = async (file: File) => {
    setCommentAttachmentUploading(true);
    try {
      const uploaded = await agentApi.files.uploadCommentAttachment(file);
      setCommentAttachments((previous) => [...previous, uploaded]);
    } catch {
      message.error(
        tw('projects.attachmentUploadFailed', 'Attachment upload failed'),
      );
    } finally {
      setCommentAttachmentUploading(false);
    }
    return false;
  };

  const downloadCommentAttachment = async (
    attachment: RequirementAttachmentVO,
  ) => {
    try {
      const blob = await agentApi.files.get(attachment.fileKey);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = attachment.fileName || attachment.fileKey;
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      message.error(
        tw('projects.attachmentDownloadFailed', 'Attachment download failed'),
      );
    }
  };

  const collectCommentMentions = (text: string): CommentMentionInput[] => {
    const mentions = new Map<string, CommentMentionInput>();
    for (const member of projectMembers) {
      const name = member.displayName || `#${member.userId}`;
      if (member.userId && text.includes(`@${name}`)) {
        mentions.set(`USER:${member.userId}`, {
          mentionType: 'USER',
          targetUserId: member.userId,
        });
      }
    }
    for (const avatar of commentAvatars) {
      if (text.includes(`@${avatar.name}`)) {
        mentions.set(`AVATAR:${avatar.agentId}`, {
          mentionType: 'AVATAR',
          targetAgentId: avatar.agentId,
        });
      }
    }
    return Array.from(mentions.values());
  };

  const commentMentionOptions = [
    ...projectMembers
      .filter((member) => member.userId)
      .map((member) => {
        const name = member.displayName || `#${member.userId}`;
        return {
          key: `mention-user-${member.userId}`,
          value: name,
          label: `@${name} · ${tw('projects.mentionMembers', 'Member')}`,
        };
      }),
    ...commentAvatars.map((avatar) => ({
      key: `mention-avatar-${avatar.agentId}`,
      value: avatar.name,
      label: `@${avatar.name} · ${
        avatar.agentType === 'LOCAL'
          ? tw('projects.mentionLocal', 'Local avatar')
          : tw('projects.mentionAvatars', 'Avatar')
      }`,
    })),
  ];

  const addRequirementComment = async () => {
    if (
      !detailRequirement ||
      (!commentDraft.trim() && commentAttachments.length === 0)
    ) {
      return;
    }
    setCommentSubmitting(true);
    try {
      const mentions = collectCommentMentions(commentDraft);
      const comment = await projectApi.addRequirementComment(
        detailRequirement.id,
        commentDraft.trim(),
        commentAttachments,
        mentions,
      );
      setComments((previous) => [...previous, comment]);
      setCommentDraft('');
      setCommentAttachments([]);
      message.success(tw('projects.commentAdded', 'Comment added'));
      if (mentions.some((mention) => mention.mentionType === 'AVATAR')) {
        window.setTimeout(() => {
          loadRequirementComments(detailRequirement.id);
        }, 10000);
      }
    } catch {
      message.error(tw('projects.commentFailed', 'Failed to add comment'));
    } finally {
      setCommentSubmitting(false);
    }
  };

  const saveRequirement = async () => {
    if (!editingRequirement) return;
    const values = await requirementEditForm.validateFields();
    const startDate = values.startDate
      ? values.startDate.format('YYYY-MM-DD')
      : undefined;
    const dueDate = values.dueDate
      ? values.dueDate.format('YYYY-MM-DD')
      : undefined;
    setRequirementSaving(true);
    try {
      await projectApi.updateRequirement(editingRequirement.id, {
        title: values.title,
        description: values.description,
        priority: values.priority ?? undefined,
        assigneeUserId: values.assigneeUserId ?? undefined,
        startDate,
        dueDate,
      });
      if (values.status && values.status !== editingRequirement.status) {
        await projectApi.changeRequirementStatus(
          editingRequirement.id,
          values.status,
        );
      }
      setRequirementEditOpen(false);
      if (detailRequirement?.id === editingRequirement.id) {
        projectApi
          .requirement(editingRequirement.id)
          .then(setDetailRequirement)
          .catch(() => undefined);
      }
      setEditingRequirement(null);
      message.success(tw('projects.requirementUpdated', 'Requirement updated'));
      loadRequirementLanes(requirementProjectFilter);
    } catch {
      message.error(tw('projects.updateFailed', 'Failed to update'));
    } finally {
      setRequirementSaving(false);
    }
  };

  const projectHandled = useRef(false);
  const requirementHandled = useRef(false);

  useEffect(() => {
    if (projectHandled.current || !initialProjectId) {
      return;
    }
    projectHandled.current = true;
    projectApi
      .detail(initialProjectId)
      .then((project) => {
        setActiveTab(REQUIREMENT_TAB);
        setRequirementProjectFilter(project.id);
        loadRequirementLanes(project.id);
      })
      .catch(() => undefined);
  }, [initialProjectId]);

  useEffect(() => {
    if (requirementHandled.current || !initialRequirementId) {
      return;
    }
    requirementHandled.current = true;
    projectApi
      .requirement(initialRequirementId)
      .then((requirement) => {
        setActiveTab(REQUIREMENT_TAB);
        openRequirementDetail(requirement);
      })
      .catch(() => undefined);
  }, [initialRequirementId]);

  const changeTab = (key: string) => {
    setActiveTab(key);
    if (key === REQUIREMENT_TAB) {
      loadRequirementLanes(requirementProjectFilter);
    }
  };

  const projectTab = (
    <>
      <div className={styles.toolbar}>
        <span className={styles.sectionLabel}>
          {tw('projects.totalProjects', '{total} projects', {
            total: projectTotal,
          })}
        </span>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={openCreateProject}
        >
          {tw('projects.create', 'New project')}
        </Button>
      </div>
      <Spin spinning={loading}>
        {projects.length === 0 ? (
          <Empty description={tw('projects.noProjects', 'No projects')} />
        ) : (
          <>
            <div className={styles.projectGrid}>
              {projects.map((project) => (
                <div
                  className={styles.projectCard}
                  key={project.id}
                  onClick={() => openProjectDetail(project)}
                >
                  <div className={styles.projectCardTop}>
                    <div className={styles.projectCardTitle}>
                      {project.name}
                    </div>
                    <Tag
                      color={
                        PROJECT_STATUS_META[project.status ?? '']?.color ||
                        'default'
                      }
                    >
                      {enumLabel(PROJECT_STATUS_LABEL_KEYS, project.status)}
                    </Tag>
                  </div>
                  <div className={styles.projectCardKey}>
                    {project.projectKey}
                  </div>
                  {project.description ? (
                    <RichTextContent value={project.description} />
                  ) : (
                    <div className={styles.projectCardDesc}>
                      {tw('projects.noDescription', 'No description')}
                    </div>
                  )}
                  <div className={styles.projectCardMeta}>
                    <span>
                      {tw('projects.owner', 'Owner')}:{' '}
                      {userLabel(project.ownerUserId, project.ownerName)}
                    </span>
                    <span>{projectPeriod(project)}</span>
                  </div>
                  <div
                    className={styles.projectCardActions}
                    onClick={(event) => event.stopPropagation()}
                  >
                    <Space size={4}>
                      <Tooltip
                        title={tw('projects.newRequirement', 'New requirement')}
                      >
                        <Button
                          className={styles.projectCardActionButton}
                          type="text"
                          icon={<FileAddOutlined />}
                          onClick={() => openCreateRequirement(project)}
                        />
                      </Tooltip>
                      <Tooltip
                        title={tw('projects.gantt', 'Requirement Gantt')}
                      >
                        <Button
                          className={styles.projectCardActionButton}
                          type="text"
                          icon={<BarChartOutlined />}
                          onClick={() => openProjectGantt(project)}
                        />
                      </Tooltip>
                      <Tooltip title={tw('projects.members', 'Members')}>
                        <Button
                          className={styles.projectCardActionButton}
                          type="text"
                          icon={<TeamOutlined />}
                          onClick={() => openProjectMembers(project)}
                        />
                      </Tooltip>
                      <Tooltip
                        title={tw(
                          'projects.assistant.open',
                          'Project Assistant',
                        )}
                      >
                        <Button
                          className={styles.projectCardActionButton}
                          type="text"
                          icon={<RobotOutlined />}
                          onClick={() => openProjectAssistant(project)}
                        />
                      </Tooltip>
                    </Space>
                    <Space size={4}>
                      <Tooltip
                        title={tw('projects.editProject', 'Edit project')}
                      >
                        <Button
                          className={styles.projectCardActionButton}
                          type="text"
                          icon={<EditOutlined />}
                          onClick={() => openEditProject(project)}
                        />
                      </Tooltip>
                      <Popconfirm
                        title={tw(
                          'projects.deleteProjectConfirm',
                          'Delete project "{name}"? Its requirements and members will no longer be accessible.',
                          { name: project.name },
                        )}
                        onConfirm={() => void deleteProject(project)}
                      >
                        <Tooltip
                          title={tw('projects.deleteProject', 'Delete project')}
                        >
                          <Button
                            className={styles.projectCardActionButton}
                            type="text"
                            danger
                            icon={<DeleteOutlined />}
                          />
                        </Tooltip>
                      </Popconfirm>
                    </Space>
                  </div>
                </div>
              ))}
            </div>
            {projectTotal > 0 ? (
              <div className={styles.pager}>
                <Pagination
                  current={projectPage}
                  pageSize={projectPageSize}
                  total={projectTotal}
                  showSizeChanger
                  pageSizeOptions={[12, 24, 48]}
                  showTotal={(total) =>
                    tw('projects.totalProjects', '{total} projects', {
                      total,
                    })
                  }
                  onChange={(page, pageSize) => loadProjects(page, pageSize)}
                />
              </div>
            ) : null}
          </>
        )}
      </Spin>
    </>
  );

  const requirementTotal = Object.values(requirementLanes).reduce(
    (sum, lane) => sum + lane.total,
    0,
  );

  const requirementTab = (
    <>
      <div className={styles.toolbar}>
        <Space wrap>
          <Select
            allowClear
            style={{ minWidth: 220 }}
            placeholder={tw('projects.filterByProject', 'Filter by project')}
            value={requirementProjectFilter}
            options={projectOptionsForSelect}
            onChange={(value?: number) => {
              setRequirementProjectFilter(value);
              loadRequirementLanes(value);
            }}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => openCreateRequirement()}
          >
            {tw('projects.newRequirement', 'New requirement')}
          </Button>
        </Space>
        <span className={styles.sectionLabel}>
          {tw('projects.totalRequirements', '{total} requirements', {
            total: requirementTotal,
          })}
        </span>
      </div>
      <Spin spinning={requirementLanesLoading}>
        <div className={styles.board}>
          {REQUIREMENT_STATUS_LIST.map((status) => {
            const meta = REQUIREMENT_STATUS_META[status];
            const lane = requirementLanes[status];
            const rows = lane?.rows || [];
            const total = lane?.total || 0;
            return (
              <div
                className={styles.boardColumn}
                key={status}
                onDragOver={(event) => event.preventDefault()}
                onDrop={() => void moveRequirementToStatus(status)}
              >
                <div
                  className={styles.boardColumnTitle}
                  style={{ color: meta.color }}
                >
                  <span>
                    {enumLabel(REQUIREMENT_STATUS_LABEL_KEYS, status)}
                  </span>
                  <span className={styles.boardCount}>{total}</span>
                </div>
                <div
                  className={styles.laneBody}
                  onScroll={(event) => onLaneScroll(event, status)}
                >
                  {rows.map((requirement) => {
                    const overdue = isOverdue(requirement);
                    return (
                      <div
                        className={styles.boardCard}
                        key={requirement.id}
                        draggable
                        onClick={() => openRequirementDetail(requirement)}
                        onDragStart={() => setDragRequirementId(requirement.id)}
                        onDragEnd={() => setDragRequirementId(null)}
                      >
                        <div className={styles.requirementCardTop}>
                          <span className={styles.requirementCardProject}>
                            {requirement.projectName ||
                              `#${requirement.projectId}`}
                          </span>
                          <Tooltip
                            title={tw(
                              'projects.editRequirement',
                              'Edit requirement',
                            )}
                          >
                            <Button
                              className={styles.projectCardActionButton}
                              type="text"
                              size="small"
                              icon={<EditOutlined />}
                              onClick={(event) => {
                                event.stopPropagation();
                                openRequirementEdit(requirement);
                              }}
                            />
                          </Tooltip>
                        </div>
                        <div className={styles.boardCardTitle}>
                          {requirement.title}
                        </div>
                        <div className={styles.boardCardTags}>
                          <Tag>{requirement.requirementKey}</Tag>
                          {requirement.priority != null ? (
                            <Tag>{`P${requirement.priority}`}</Tag>
                          ) : null}
                          {overdue ? (
                            <Tag color="red">
                              {tw('projects.overdue', 'Overdue')}
                            </Tag>
                          ) : null}
                        </div>
                        <div className={styles.boardCardMeta}>
                          <span>
                            {userLabel(
                              requirement.assigneeUserId,
                              requirement.assigneeName,
                            )}
                          </span>
                          {requirement.startDate || requirement.dueDate ? (
                            <span
                              className={overdue ? styles.overdue : undefined}
                            >
                              {requirement.startDate || '-'} ~{' '}
                              {requirement.dueDate || '-'}
                            </span>
                          ) : null}
                        </div>
                      </div>
                    );
                  })}
                  {rows.length === 0 ? (
                    <div className={styles.boardEmpty}>
                      {tw('projects.boardEmpty', 'No records')}
                    </div>
                  ) : null}
                  {lane?.loading ? (
                    <div className={styles.laneLoading}>
                      <Spin size="small" />
                    </div>
                  ) : null}
                  {!lane?.loading && lane && rows.length < total ? (
                    <div className={styles.laneHint}>
                      {tw('projects.scrollForMore', 'Scroll for more')}
                    </div>
                  ) : null}
                </div>
              </div>
            );
          })}
        </div>
      </Spin>
    </>
  );

  const requirementDetailPage = detailRequirement ? (
    <div className={styles.requirementDetailPage}>
      <div className={styles.requirementDetailMain}>
        <div className={styles.requirementDetailHeading}>
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => setDetailRequirement(null)}
          />
          <div className={styles.requirementDetailTitleBlock}>
            <div className={styles.requirementDetailTitle}>
              {detailRequirement.title}
            </div>
            <div className={styles.requirementDetailSubtitle}>
              {detailRequirement.projectName ||
                `#${detailRequirement.projectId}`}
            </div>
          </div>
          <Button
            icon={<EditOutlined />}
            onClick={() => openRequirementEdit(detailRequirement)}
          >
            {tw('projects.editRequirement', 'Edit requirement')}
          </Button>
        </div>

        <div className={styles.detailMetaTags}>
          <Tag>{detailRequirement.requirementKey}</Tag>
          <Tag color={REQUIREMENT_STATUS_META[detailRequirement.status]?.color}>
            {enumLabel(REQUIREMENT_STATUS_LABEL_KEYS, detailRequirement.status)}
          </Tag>
          <Tag>{detailRequirement.projectName || ''}</Tag>
        </div>

        <Divider>{tw('projects.description', 'Description')}</Divider>
        {detailRequirement.description ? (
          <RichTextContent value={detailRequirement.description} expanded />
        ) : (
          <div className={styles.rowDesc}>
            {tw('projects.noDescription', 'No description')}
          </div>
        )}

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            marginTop: 12,
          }}
        >
          <Divider style={{ margin: 0, flex: 1 }}>
            {tw('projects.comments', 'Comments')}
          </Divider>
          <Button
            type="text"
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => {
              if (detailRequirement) {
                loadRequirementComments(detailRequirement.id);
              }
            }}
          >
            {tw('projects.refresh', 'Refresh')}
          </Button>
        </div>
        <div className={styles.commentList}>
          <Spin spinning={commentsLoading}>
            {comments.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={tw('projects.noComments', 'No comments')}
              />
            ) : (
              comments.map((comment) => (
                <div
                  className={styles.commentItem}
                  key={comment.id}
                  style={
                    comment.parentCommentId
                      ? {
                          marginLeft: 20,
                          paddingLeft: 12,
                          borderLeft: '2px solid #ece7fb',
                        }
                      : undefined
                  }
                >
                  <div className={styles.commentMeta}>
                    <Space size={6} wrap>
                      <strong>
                        {comment.authorType === 'ASSISTANT'
                          ? tw('projects.assistantBadge', 'Project Assistant')
                          : comment.authorName ||
                            comment.userName ||
                            (comment.userId ? `#${comment.userId}` : '-')}
                      </strong>
                      {comment.authorType === 'ASSISTANT' ? (
                        <Tag bordered={false} color="blue">
                          {tw('projects.assistantBadge', 'Project Assistant')}
                        </Tag>
                      ) : null}
                      {comment.authorType === 'AVATAR' ? (
                        <Tag bordered={false} color="purple">
                          {tw('projects.avatarBadge', 'Avatar')}
                        </Tag>
                      ) : null}
                    </Space>
                    <span>
                      {comment.createdAt
                        ? dayjs(comment.createdAt).format('YYYY-MM-DD HH:mm')
                        : ''}
                    </span>
                  </div>
                  <div className={styles.commentContent}>{comment.content}</div>
                  {comment.mentions?.length ? (
                    <div className={styles.commentAttachments}>
                      {comment.mentions.map((mention) => (
                        <Tooltip
                          key={mention.id}
                          title={mention.errorMessage || undefined}
                        >
                          <Tag
                            bordered={false}
                            color={
                              mention.status === 'FAILED' ? 'red' : 'geekblue'
                            }
                          >
                            @
                            {mention.targetAgentName ||
                              mention.targetUserName ||
                              mention.targetAgentId ||
                              mention.targetUserId}
                            {mention.status === 'FAILED'
                              ? ` · ${tw(
                                  'projects.mentionFailed',
                                  'Delivery failed',
                                )}`
                              : ''}
                          </Tag>
                        </Tooltip>
                      ))}
                    </div>
                  ) : null}
                  {comment.attachments?.length ? (
                    <div className={styles.commentAttachments}>
                      {comment.attachments.map((attachment) => (
                        <Button
                          key={attachment.fileKey}
                          type="link"
                          size="small"
                          icon={<PaperClipOutlined />}
                          onClick={() =>
                            void downloadCommentAttachment(attachment)
                          }
                        >
                          {attachment.fileName || attachment.fileKey}
                        </Button>
                      ))}
                    </div>
                  ) : null}
                </div>
              ))
            )}
          </Spin>
        </div>
        <div className={styles.commentComposer}>
          {commentAttachments.length ? (
            <div className={styles.commentAttachmentList}>
              {commentAttachments.map((attachment) => (
                <Tag
                  key={attachment.fileKey}
                  icon={<PaperClipOutlined />}
                  closable
                  onClose={() =>
                    setCommentAttachments((previous) =>
                      previous.filter(
                        (item) => item.fileKey !== attachment.fileKey,
                      ),
                    )
                  }
                >
                  {attachment.fileName || attachment.fileKey}
                </Tag>
              ))}
            </div>
          ) : null}
          <Mentions
            rows={3}
            style={{ width: '100%', resize: 'none' }}
            value={commentDraft}
            placeholder={tw(
              'projects.commentPlaceholder',
              'Write a comment, type @ to notify a member or avatar...',
            )}
            prefix="@"
            split=" "
            options={commentMentionOptions}
            onChange={(value) => setCommentDraft(value)}
          />
          <div className={styles.commentComposerActions}>
            <Upload
              showUploadList={false}
              beforeUpload={(file) => {
                void uploadCommentAttachment(file);
                return false;
              }}
            >
              <Button
                icon={<PaperClipOutlined />}
                loading={commentAttachmentUploading}
              >
                {tw('projects.addAttachment', 'Attach file')}
              </Button>
            </Upload>
            <Button
              type="primary"
              loading={commentSubmitting}
              disabled={!commentDraft.trim() && commentAttachments.length === 0}
              onClick={() => void addRequirementComment()}
            >
              {tw('projects.addComment', 'Add comment')}
            </Button>
          </div>
        </div>
      </div>

      <aside className={styles.requirementDetailAside}>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.status', 'Status')}
          </span>
          <Tag color={REQUIREMENT_STATUS_META[detailRequirement.status]?.color}>
            {enumLabel(REQUIREMENT_STATUS_LABEL_KEYS, detailRequirement.status)}
          </Tag>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.assignee', 'Assignee')}
          </span>
          <span className={styles.requirementSideValue}>
            {userLabel(
              detailRequirement.assigneeUserId,
              detailRequirement.assigneeName,
            )}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.priority', 'Priority')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailRequirement.priority ?? '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.startDate', 'Start date')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailRequirement.startDate || '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.dueDate', 'Due date')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailRequirement.dueDate || '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.requirementProject', 'Project')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailRequirement.projectName || `#${detailRequirement.projectId}`}
          </span>
        </div>

        <Divider />
        <div className={styles.requirementSideSectionTitle}>
          {tw('projects.details', 'Details')}
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.creator', 'Creator')}
          </span>
          <span className={styles.requirementSideValue}>
            {userLabel(
              detailRequirement.ownerUserId,
              detailRequirement.ownerName,
            )}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.createdAt', 'Created')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailRequirement.createdAt
              ? dayjs(detailRequirement.createdAt).format('YYYY-MM-DD HH:mm')
              : '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.updatedAt', 'Updated')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailRequirement.updatedAt
              ? dayjs(detailRequirement.updatedAt).format('YYYY-MM-DD HH:mm')
              : '-'}
          </span>
        </div>
      </aside>
    </div>
  ) : null;

  const projectDetailPage = detailProject ? (
    <div className={styles.requirementDetailPage}>
      <div className={styles.requirementDetailMain}>
        <div className={styles.requirementDetailHeading}>
          <Button
            type="text"
            icon={<ArrowLeftOutlined />}
            onClick={() => setDetailProject(null)}
          />
          <div className={styles.requirementDetailTitleBlock}>
            <div className={styles.requirementDetailTitle}>
              {detailProject.name}
            </div>
            <div className={styles.requirementDetailSubtitle}>
              {detailProject.projectKey}
            </div>
          </div>
          <Space>
            <Button
              icon={<RobotOutlined />}
              onClick={() => openProjectAssistant(detailProject)}
            >
              {tw('projects.assistant.open', 'Project Assistant')}
            </Button>
            <Button
              icon={<BarChartOutlined />}
              onClick={() => openProjectGantt(detailProject)}
            >
              {tw('projects.gantt', 'Requirement Gantt')}
            </Button>
            <Button
              icon={<EditOutlined />}
              onClick={() => openEditProject(detailProject)}
            >
              {tw('projects.editProject', 'Edit project')}
            </Button>
          </Space>
        </div>

        <div className={styles.detailMetaTags}>
          <Tag>{detailProject.projectKey}</Tag>
          <Tag color={PROJECT_STATUS_META[detailProject.status ?? '']?.color}>
            {enumLabel(PROJECT_STATUS_LABEL_KEYS, detailProject.status)}
          </Tag>
        </div>

        <Divider>{tw('projects.description', 'Description')}</Divider>
        {detailProject.description ? (
          <RichTextContent value={detailProject.description} expanded />
        ) : (
          <div className={styles.rowDesc}>
            {tw('projects.noDescription', 'No description')}
          </div>
        )}
      </div>

      <aside className={styles.requirementDetailAside}>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.status', 'Status')}
          </span>
          <Tag color={PROJECT_STATUS_META[detailProject.status ?? '']?.color}>
            {enumLabel(PROJECT_STATUS_LABEL_KEYS, detailProject.status)}
          </Tag>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.owner', 'Owner')}
          </span>
          <span className={styles.requirementSideValue}>
            {userLabel(detailProject.ownerUserId, detailProject.ownerName)}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.startDate', 'Start date')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailProject.startDate || '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.endDate', 'End date')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailProject.endDate || '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.members', 'Members')}
          </span>
          <span className={styles.requirementSideValue}>
            {projectDetailMembers.length}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.requirements', 'Requirements')}
          </span>
          <span className={styles.requirementSideValue}>
            {projectDetailRequirements.length}
          </span>
        </div>
        <Divider />
        <div className={styles.requirementSideSectionTitle}>
          {tw('projects.details', 'Details')}
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.createdAt', 'Created')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailProject.createdAt
              ? dayjs(detailProject.createdAt).format('YYYY-MM-DD HH:mm')
              : '-'}
          </span>
        </div>
        <div className={styles.requirementSideField}>
          <span className={styles.requirementSideLabel}>
            {tw('projects.updatedAt', 'Updated')}
          </span>
          <span className={styles.requirementSideValue}>
            {detailProject.updatedAt
              ? dayjs(detailProject.updatedAt).format('YYYY-MM-DD HH:mm')
              : '-'}
          </span>
        </div>
        <Divider />
        <div className={styles.requirementSideSectionTitle}>
          {tw('projects.members', 'Members')}
        </div>
        <Space wrap>
          {projectDetailMembers.map((member) => (
            <Tag key={member.userId}>
              {member.displayName || `#${member.userId}`}
            </Tag>
          ))}
        </Space>
      </aside>
    </div>
  ) : null;

  return (
    <div className={styles.shell}>
      {detailRequirement
        ? requirementDetailPage
        : detailProject
          ? projectDetailPage
          : null}
      {!detailRequirement && !detailProject ? (
        <>
          <div className={styles.pageHead}>
            <div className={styles.detailHead}>
              {onBack ? (
                <Tooltip
                  title={tw('projects.backToApps', 'Back to App Center')}
                >
                  <Button
                    type="text"
                    icon={<ArrowLeftOutlined />}
                    onClick={onBack}
                  />
                </Tooltip>
              ) : null}
              <div>
                <div className={styles.title}>
                  {tw('projects.title', 'Project Management')}
                </div>
                <div className={styles.subtitle}>
                  {tw(
                    'projects.subtitle',
                    'Projects, requirements, members and project agents',
                  )}
                </div>
              </div>
            </div>
          </div>

          <Tabs
            activeKey={activeTab}
            onChange={changeTab}
            items={[
              {
                key: PROJECT_TAB,
                label: tw('projects.tabProjects', 'Projects'),
                children: projectTab,
              },
              {
                key: REQUIREMENT_TAB,
                label: tw('projects.tabRequirements', 'Requirements'),
                children: requirementTab,
              },
            ]}
          />
        </>
      ) : null}

      <Modal
        open={projectFormOpen}
        title={
          editingProject
            ? tw('projects.editProject', 'Edit project')
            : tw('projects.create', 'New project')
        }
        onCancel={() => {
          setProjectFormOpen(false);
          setEditingProject(null);
        }}
        onOk={() => void saveProject()}
        confirmLoading={projectSaving}
        destroyOnHidden
      >
        {!editingProject ? (
          <div className={styles.rowDesc} style={{ marginBottom: 12 }}>
            {tw(
              'projects.projectKeyAuto',
              'Project key is generated automatically',
            )}
          </div>
        ) : null}
        <Form form={projectForm} layout="vertical">
          <Form.Item
            name="name"
            label={tw('projects.name', 'Project name')}
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={tw('projects.description', 'Description')}
          >
            <RichTextEditor />
          </Form.Item>
          <Form.Item
            name="startDate"
            label={tw('projects.startDate', 'Start date')}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="endDate" label={tw('projects.endDate', 'End date')}>
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={requirementOpen}
        width={780}
        title={tw('projects.newRequirement', 'New requirement')}
        onCancel={() => setRequirementOpen(false)}
        onOk={() => void createRequirement()}
        confirmLoading={requirementCreating}
        destroyOnHidden
      >
        <Form form={requirementForm} layout="vertical">
          <Form.Item
            name="projectId"
            label={tw('projects.requirementProject', 'Project')}
            rules={[{ required: true }]}
          >
            <Select
              placeholder={tw('projects.selectProject', 'Select project')}
              options={projectOptionsForSelect}
              onChange={changeRequirementProject}
            />
          </Form.Item>
          <Form.Item
            name="title"
            label={tw('projects.requirementTitle', 'Requirement title')}
            rules={[{ required: true }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={tw('projects.description', 'Description')}
          >
            <RichTextEditor minHeight={220} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={6}>
              <Form.Item
                name="assigneeUserId"
                label={tw('projects.assignee', 'Assignee')}
              >
                <Select
                  allowClear
                  options={assigneeOptions}
                  onDropdownVisibleChange={(open: boolean) => {
                    if (open) {
                      refreshAssigneeOptions(
                        requirementForm.getFieldValue('projectId'),
                      );
                    }
                  }}
                />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="priority"
                label={tw('projects.priority', 'Priority')}
              >
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="startDate"
                label={tw('projects.startDate', 'Start date')}
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                name="dueDate"
                label={tw('projects.dueDate', 'Due date')}
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Drawer
        open={requirementEditOpen}
        width={640}
        title={tw('projects.editRequirement', 'Edit requirement')}
        onClose={() => setRequirementEditOpen(false)}
        destroyOnHidden
        rootClassName={styles.appDrawerRoot}
        getContainer={() =>
          document.getElementById('workspace-main') ?? document.body
        }
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setRequirementEditOpen(false)}>
              {tw('projects.cancel', 'Cancel')}
            </Button>
            <Button
              type="primary"
              loading={requirementSaving}
              onClick={() => void saveRequirement()}
            >
              {tw('projects.save', 'Save')}
            </Button>
          </Space>
        }
      >
        {editingRequirement ? (
          <>
            <div className={styles.rowDesc}>
              <Tag>{editingRequirement.requirementKey}</Tag>
              {tw('projects.requirementProject', 'Project')}:{' '}
              {editingRequirement.projectName ||
                `#${editingRequirement.projectId}`}{' '}
              路 {tw('projects.creator', 'Creator')}:{' '}
              {userLabel(
                editingRequirement.ownerUserId,
                editingRequirement.ownerName,
              )}
            </div>
            <Form
              form={requirementEditForm}
              layout="vertical"
              style={{ marginTop: 16 }}
            >
              <Form.Item
                name="title"
                label={tw('projects.requirementTitle', 'Requirement title')}
                rules={[{ required: true }]}
              >
                <Input />
              </Form.Item>
              <Row gutter={12}>
                <Col span={8}>
                  <Form.Item
                    name="status"
                    label={tw('projects.status', 'Status')}
                  >
                    <Select
                      options={REQUIREMENT_STATUS_LIST.map((status) => ({
                        value: status,
                        label: enumLabel(REQUIREMENT_STATUS_LABEL_KEYS, status),
                      }))}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item
                    name="assigneeUserId"
                    label={tw('projects.assignee', 'Assignee')}
                  >
                    <Select
                      allowClear
                      options={assigneeOptions}
                      onDropdownVisibleChange={(open: boolean) => {
                        if (open) {
                          refreshAssigneeOptions(editingRequirement.projectId);
                        }
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item
                    name="priority"
                    label={tw('projects.priority', 'Priority')}
                  >
                    <InputNumber min={0} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>
              <Row gutter={12}>
                <Col span={8}>
                  <Form.Item
                    name="startDate"
                    label={tw('projects.startDate', 'Start date')}
                  >
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item
                    name="dueDate"
                    label={tw('projects.dueDate', 'Due date')}
                  >
                    <DatePicker style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>
              <Form.Item
                name="description"
                label={tw('projects.description', 'Description')}
              >
                <RichTextEditor minHeight={220} />
              </Form.Item>
            </Form>
          </>
        ) : null}
      </Drawer>

      <Drawer
        open={ganttOpen}
        width={900}
        title={`${ganttProject?.name || ''} 路 ${tw(
          'projects.gantt',
          'Requirement Gantt',
        )}`}
        onClose={() => setGanttOpen(false)}
        destroyOnHidden
        rootClassName={styles.appDrawerRoot}
        getContainer={() =>
          document.getElementById('workspace-main') ?? document.body
        }
      >
        <Spin spinning={ganttLoading}>
          <RequirementGantt
            requirements={ganttRequirements}
            onMove={moveGanttRequirement}
          />
        </Spin>
      </Drawer>

      {selectedProject ? (
        <MemberManagerModal
          open={membersOpen}
          title={`${selectedProject.name} 路 ${tw('projects.members', 'Members')}`}
          load={() => projectApi.members(selectedProject.id)}
          add={(userId) => projectApi.addMember(selectedProject.id, userId)}
          remove={(userId) =>
            projectApi.removeMember(selectedProject.id, userId)
          }
          onClose={() => setMembersOpen(false)}
          onChanged={() => {
            refreshProjectMembers(selectedProject.id);
            loadContacts();
          }}
        />
      ) : null}

      <ProjectAssistantDrawer
        open={assistantOpen}
        projectId={assistantProject?.id}
        projectName={assistantProject?.name}
        initialTab={assistantTab}
        onClose={() => setAssistantOpen(false)}
      />
    </div>
  );
}
