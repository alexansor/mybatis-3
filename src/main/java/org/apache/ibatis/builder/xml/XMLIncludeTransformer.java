/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  // 解析include标签入口
  public void applyIncludes(Node source) {
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      variablesContext.putAll(configurationVariables);
    }
    applyIncludes(source, variablesContext, false);
  }

  /**
   * 解析include标签方法，递归解析内部子标签
   * 刚开始传进来一个select节点，走第一个else if
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    // 如果是include节点
    if (source.getNodeName().equals("include")) {
      // 找到id为refid值的sql段，是一个node，在上一步处理sql标签时已经得到
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      // 获取表达式值
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      // 递归调用处理sql内部的include标签
      applyIncludes(toInclude, toIncludeContext, true);
      // 如果toInclude文档不是当前文档，则将toInclude节点深度复制到当前文档，源文档节点不会被删除，因为这里是要将sql里
      // 的信息复制到当前节点中，原sql节点不能删除
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      // 使用已经解析好的节点代替原来的节点
      source.getParentNode().replaceChild(toInclude, source);
      // 如果替换的几点包含子节点，则循环将它的子节点移动到它的上面，作为兄弟节点，然后将节点删除
      // <sql id="WHERE_STATUS_PARAM">status in <include refid="WHERE_STATUS_VALUE" /></sql>
      // <sql id="WHERE_STATUS_VALUE>(1, 2, 3)</sql>
      while (toInclude.hasChildNodes()) { 
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      // 替换完成，删除原节点
      toInclude.getParentNode().removeChild(toInclude);
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      // 如果不是include节点，是node节点，则对节点中属性表达式的值进行替换
      // 比如说是sql节点，替换属性值
      // 比如<sql refid="${SOME_SQL}"></sql>
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        // 获取所有属性
        NamedNodeMap attributes = source.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          // 循环设置属性值
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      // 获取子节点，递归调用本方法
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && source.getNodeType() == Node.TEXT_NODE
        && !variablesContext.isEmpty()) {
      // 如果是递归进来的，且是text节点接，并且属性表不为空，则替换表达式值
      // 比如处理<sql>标签的内容
      // 比如<select id="selectById">select * from ${table_name}</select>，就是在这里对表达式进行替换的
      // replace variables in text node
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  /**
   * 获取sql段
   * @param refid
   * @param variables
   * @return
   */
  private Node findSqlFragment(String refid, Properties variables) {
    // 将变量解析为常量
    refid = PropertyParser.parse(refid, variables);
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  /**
   * 获取refid属性的值，可以是固定值，也可以试properties中定义的变量
   * 比如：refid="base_column"  refid="${column_var}"
   * @param node
   * @param name
   * @return
   */
  private String getStringAttribute(Node node, String name) {
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * 替换表达式后得到新标签
   * Read placeholders and their values from include node definition. 
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    Map<String, String> declaredProperties = null;
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node n = children.item(i);
      // 如果是node节点
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        String name = getStringAttribute(n, "name");
        // Replace variables inside
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<String, String>();
        }
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
