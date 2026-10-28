# 管理器包名的内核侧取值逻辑，全局仅此一处。
#
# 默认值不在本文件里，来自仓库根的 manager-package.properties（单一真源）；
# 本文件只负责“按 KSU_IS_PR_BUILD 选哪个键”，以及“文件缺失时立刻报错”。
#
# 两个使用者：
#   1. Kbuild：include 本文件，命令行传入的 KSU_PACKAGE_NAME 仍然优先（?= 不覆盖）。
#   2. scripts/check_manager_package.py：用
#        make -s -f kernel/manager-package.mk KSU_MANAGER_PACKAGE_MK_STANDALONE=1 \
#             KSU_IS_PR_BUILD=<0|1> print-ksu-package-name
#      独立求值，从而校验“内核侧真正会编译进去的值”，而不是在 Python 里重写一遍逻辑。
#
# 注意路径解析：kernel/ 可能通过符号链接被挂进内核树（kernel/setup.sh 的做法），
# 此时 `drivers/kernelsu` 里的 `..` 不会穿过符号链接。所以这里用 realpath 把
# 本文件自身解析成真实路径，再向上一级找真源文件。

# $(lastword $(MAKEFILE_LIST)) 在本文件被读入时就是本文件自己。
KSU_MANAGER_PACKAGE_MK := $(realpath $(lastword $(MAKEFILE_LIST)))
KSU_MANAGER_PACKAGE_ROOT := $(patsubst %/,%,$(dir $(KSU_MANAGER_PACKAGE_MK)))
KSU_MANAGER_PACKAGE_SINGLE_SOURCE ?= $(realpath $(KSU_MANAGER_PACKAGE_ROOT)/../manager-package.properties)

ifeq ($(strip $(KSU_MANAGER_PACKAGE_SINGLE_SOURCE)),)
$(error Cannot find manager-package.properties next to $(KSU_MANAGER_PACKAGE_ROOT); \
copy it to the repository root, or pass KSU_PACKAGE_NAME=<package> explicitly)
endif

include $(KSU_MANAGER_PACKAGE_SINGLE_SOURCE)

# 防御 CRLF 检出（Windows 本地构建）：值里混进 \r 会让内核宏变成 "...ultra\r"，
# 认主时 strncmp 永远失败，且编译、打包全程不报错。
KSU_MANAGER_PACKAGE_NAME := $(strip $(KSU_MANAGER_PACKAGE_NAME))
KSU_MANAGER_PACKAGE_NAME_PR := $(strip $(KSU_MANAGER_PACKAGE_NAME_PR))

KSU_IS_PR_BUILD ?= 0

ifeq ($(KSU_IS_PR_BUILD),0)
KSU_PACKAGE_NAME ?= $(KSU_MANAGER_PACKAGE_NAME)
else
KSU_PACKAGE_NAME ?= $(KSU_MANAGER_PACKAGE_NAME_PR)
endif

ifeq ($(strip $(KSU_PACKAGE_NAME)),)
$(error KSU_PACKAGE_NAME resolved to an empty value; check $(KSU_MANAGER_PACKAGE_SINGLE_SOURCE))
endif

# 校验用的独立目标：只有显式请求时才定义，避免往 kbuild 的目标图里塞东西。
ifdef KSU_MANAGER_PACKAGE_MK_STANDALONE
.PHONY: print-ksu-package-name
print-ksu-package-name:
	@echo $(KSU_PACKAGE_NAME)
endif
