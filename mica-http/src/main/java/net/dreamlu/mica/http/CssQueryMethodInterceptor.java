/*
 * Copyright (c) 2019-2029, Dreamlu (596392912@qq.com & www.dreamlu.net).
 * <p>
 * Licensed under the GNU LESSER GENERAL PUBLIC LICENSE 3.0;
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.gnu.org/licenses/lgpl.html
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dreamlu.mica.http;

import lombok.RequiredArgsConstructor;
import net.dreamlu.mica.core.utils.ConvertUtil;
import net.dreamlu.mica.core.utils.StringUtil;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.TypeDescriptor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 代理模型
 *
 * @author L.cm
 */
@RequiredArgsConstructor
public class CssQueryMethodInterceptor implements MethodInterceptor {
	private final Class<?> clazz;
	private final Element element;

	@Nullable
	@Override
	public Object intercept(Object object, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
		// 只处理 get 方法 is
		String name = method.getName();
		if (!name.startsWith("get")) {
			return methodProxy.invokeSuper(object, args);
		}
		Field field = clazz.getDeclaredField(StringUtil.firstCharToLower(name.substring(3)));
		CssQuery annotation = field.getAnnotation(CssQuery.class);
		// 没有注解，不代理
		if (annotation == null) {
			return methodProxy.invokeSuper(object, args);
		}
		Class<?> returnType = method.getReturnType();
		boolean isColl = Collection.class.isAssignableFrom(returnType);
		String cssQueryValue = annotation.value();
		// 是否为 bean 中 bean
		boolean isInner = annotation.inner();
		if (isInner) {
			return proxyInner(cssQueryValue, method, returnType, isColl);
		}
		String attrName = annotation.attr();
		String valueRegex = annotation.regex();
		Object proxyValue = proxyValue(cssQueryValue, attrName, valueRegex, returnType, isColl);
		if (String.class.isAssignableFrom(returnType)) {
			return proxyValue;
		}
		// 用于读取 field 上的注解
		TypeDescriptor typeDescriptor = new TypeDescriptor(field);
		return ConvertUtil.convert(proxyValue, typeDescriptor);
	}

	@Nullable
	private Object proxyValue(String cssQueryValue, String attrName, String valueRegex, Class<?> returnType, boolean isColl) {
		if (isColl) {
			Elements elements = Selector.select(cssQueryValue, element);
			Collection<Object> valueList = newColl(returnType);
			if (elements.isEmpty()) {
				return valueList;
			}
			for (Element select : elements) {
				String value = getValue(select, attrName, valueRegex);
				if (value != null) {
					valueList.add(value);
				}
			}
			return valueList;
		}
		Element select = Selector.selectFirst(cssQueryValue, element);
		return getValue(select, attrName, valueRegex);
	}

	private Object proxyInner(String cssQueryValue, Method method, Class<?> returnType, boolean isColl) {
		if (isColl) {
			Elements elements = Selector.select(cssQueryValue, element);
			Collection<Object> valueList = newColl(returnType);
			ResolvableType resolvableType = ResolvableType.forMethodReturnType(method);
			Class<?> innerType = resolvableType.getGeneric(0).resolve();
			if (innerType == null) {
				throw new IllegalArgumentException("Class " + returnType + " 读取泛型失败。");
			}
			for (Element select : elements) {
				valueList.add(DomMapper.readValue(select, innerType));
			}
			return valueList;
		}
		Element select = Selector.selectFirst(cssQueryValue, element);
		return DomMapper.readValue(select, returnType);
	}

	@Nullable
	private String getValue(@Nullable Element element, String attrName, String valueRegex) {
		if (element == null) {
			return null;
		}
		String attrValue;
		if (StringUtil.isBlank(attrName)) {
			attrValue = element.outerHtml();
		} else if ("html".equalsIgnoreCase(attrName)) {
			attrValue = element.html();
		} else if ("text".equalsIgnoreCase(attrName)) {
			attrValue = getText(element);
		} else if ("allText".equalsIgnoreCase(attrName)) {
			attrValue = element.text();
		} else {
			attrValue = element.attr(attrName);
		}
		if (StringUtil.isBlank(attrValue) || StringUtil.isBlank(valueRegex)) {
			return attrValue;
		}
		// 处理正则表达式
		Matcher matcher = Pattern.compile(valueRegex).matcher(attrValue);
		if (matcher.find()) {
			return matcher.group();
		}
		return null;
	}

	private String getText(Element element) {
		return element.childNodes().stream()
			.filter(node -> node instanceof TextNode)
			.map(node -> (TextNode) node)
			.map(TextNode::text)
			.collect(Collectors.joining());
	}

	private Collection<Object> newColl(Class<?> returnType) {
		return Set.class.isAssignableFrom(returnType) ? new HashSet<>() : new ArrayList<>();
	}
}
