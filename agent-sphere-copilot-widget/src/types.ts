export interface UserVO {
  id: number;
  username: string;
  displayName: string;
  email: string;
  avatar: string;
  token: string;
  status: string;
}

export interface InstanceVO {
  id: number;
  name: string;
  description: string;
  systemPrompt: string;
  image: string;
  status: string;
  createdAt: string;
  createdBy: string;
  updatedAt: string;
}

export interface SessionVO {
  id: number;
  title: string;
  agentInstanceId: number;
  status: string;
  createdAt: string;
  updatedAt: string;
}
