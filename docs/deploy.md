# 部署指导（阿里云 / 腾讯云 · Docker Compose）

> 目标：一台 2C4G 的 Linux 服务器即可。前端 Nginx 托管静态页并反代后端，MySQL 与上传目录都挂在数据卷上，迁移时整个项目目录拷走即可。

## 1. 服务器准备

1. 购买云服务器（推荐 2核4G、Ubuntu 22.04 / Debian 12 / CentOS 7.9+），**安全组放行端口：80（HTTP）、443（HTTPS）、22（SSH）**。
2. SSH 登录后更新系统并安装 Docker 与 Compose 插件：

```bash
# Ubuntu / Debian
sudo apt update && sudo apt install -y docker.io docker-compose-v2
sudo systemctl enable --now docker

# CentOS / 阿里云 Alinux
# curl -fsSL https://get.docker.com | bash -s docker
# sudo systemctl enable --now docker
```

验证：`docker --version && docker compose version`（compose 是 v2 子命令）。

## 2. 上传项目

在本机项目根目录执行（或用 WinSCP / FinalShell 上传整个项目目录）：

```bash
# 方式一：git 仓库
git clone <你的仓库地址> couple-site && cd couple-site

# 方式二：scp 直传（在项目父目录执行）
scp -r demo root@<服务器IP>:/opt/couple-site
```

## 3. 配置环境变量

```bash
cd /opt/couple-site
cp .env.example .env
vim .env   # 修改 MYSQL_ROOT_PASSWORD / MYSQL_PASSWORD / JWT_SECRET 三个必改项
```

生成高强度 JWT 密钥：`openssl rand -base64 32`，把输出粘贴到 `JWT_SECRET=`。

## 4. 构建并启动

```bash
docker compose up -d --build
```

首次启动会自动：
- 创建 MySQL 容器并执行 `sql/init.sql`（建库建表，仅空数据卷时执行）；
- 构建后端镜像（Maven 打包，需几分钟）并等待 MySQL 健康后启动；
- 构建前端镜像（npm install + build）并以 Nginx 托管。

检查状态：

```bash
docker compose ps          # 三个服务都应为 running / healthy
docker compose logs -f backend   # 看后端日志，出现 Started CoupleApplication 即成功
```

## 5. 验证

浏览器访问 `http://<服务器IP>/`：
1. 注册第一个账号 → 页面展示**情侣码**；
2. 用另一浏览器（或手机）注册第二个账号 → 输入情侣码绑定；
3. 发布一条动态、上传一张照片验证上传目录（`./data/uploads`）可用。

## 6. 域名 + HTTPS（推荐，正式使用必需）

```bash
# 1. 域名解析 A 记录指向服务器 IP，等待生效
# 2. 安装 certbot（Ubuntu/Debian）
sudo apt install -y certbot python3-certbot-nginx

# 3. 自动申请证书并改写 Nginx 配置（需先让 80 端口可访问）
sudo certbot --nginx -d yourdomain.com

# 4. 证书续期（certbot 自带 systemd timer，检查即可）
sudo systemctl list-timers | grep certbot
```

> 注意：certbot 改的是宿主机的 Nginx；本项目前端跑在 Docker 容器里，更简单的做法是**把容器的 80/443 直接映射到宿主机**，然后按官方文档在宿主机装 Nginx 做一层反代到 `127.0.0.1:80`（容器）。或直接改用 `nginx-proxy` / `traefik` 容器方案自动签发证书（进阶，见 roadmap）。

## 7. 日常运维

```bash
docker compose logs -f backend          # 看后端日志
docker compose restart backend          # 重启后端
docker compose pull && docker compose up -d --build   # 升级（重新构建）

# 数据备份（建议 crontab 每天执行）
docker exec couple-mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" couple' > backup_$(date +%F).sql
# 上传目录备份
tar czf uploads_$(date +%F).tar.gz data/uploads
```

## 8. 迁移到新服务器

```bash
# 旧服务器：停服后打包
docker compose down
tar czf couple-backup.tar.gz data/ sql/ docker-compose.yml .env

# 新服务器：解压到 /opt/couple-site 后
docker compose up -d
```

数据卷（MySQL 数据 + 上传文件）与项目代码一起迁移，业务零丢失。

## 9. 常见问题

| 现象 | 处理 |
|------|------|
| 后端连不上 MySQL | `docker compose logs backend` 看 URL/账号密码是否与 `.env` 一致；MySQL 健康检查是否通过 |
| 图片上传失败 | 检查后端 multipart 与 Nginx `client_max_body_size` 是否够大；`data/uploads` 目录权限 |
| 前端白屏 / 刷新 404 | Nginx `try_files ... /index.html` 已处理 SPA 回退；确认访问的是 80 端口 |
| 登录后接口 401 | 检查服务器时间（JWT 校验依赖时钟）：`timedatectl set-ntp true` |
| 想改端口 | 改 `docker-compose.yml` 中 frontend 的 `"80:80"` 映射，如 `"8081:80"` |
