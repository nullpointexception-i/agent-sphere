FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

# fholzer/nginx-brotli：官方 nginx + ngx_brotli（brotli 压缩），目录结构兼容
# 注意：该镜像 ENTRYPOINT 即 nginx 二进制，CMD 只能给参数（不能重复写 "nginx"，
# 否则执行成 `nginx nginx -g ...` → "invalid option: nginx" → 容器启动失败）
FROM fholzer/nginx-brotli:latest
COPY --from=build /app/dist /usr/share/nginx/html
COPY --from=build /app/public/index.html /usr/share/nginx/html/index.html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# 移除 Debian 版 nginx 自带默认 site（default_server + server_name _），避免抢占请求
RUN rm -f /etc/nginx/sites-enabled/default
EXPOSE 80
CMD ["-g", "daemon off;"]
